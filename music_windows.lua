-- SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
-- SPDX-License-Identifier: GPL-3.0-or-later

-- Windows anonymous pipes keep the Python backend off the UI thread.
local ffi = require('ffi')
ffi.cdef[[
typedef void * HANDLE;
typedef unsigned long DWORD;
typedef int BOOL;
typedef unsigned short WCHAR;
typedef struct { DWORD nLength; void *lpSecurityDescriptor; BOOL bInheritHandle; } SECURITY_ATTRIBUTES;
typedef struct {
 DWORD cb; WCHAR *lpReserved; WCHAR *lpDesktop; WCHAR *lpTitle;
 DWORD dwX,dwY,dwXSize,dwYSize,dwXCountChars,dwYCountChars,dwFillAttribute,dwFlags;
 unsigned short wShowWindow,cbReserved2; unsigned char *lpReserved2;
 HANDLE hStdInput,hStdOutput,hStdError;
} STARTUPINFOW;
typedef struct { HANDLE hProcess,hThread; DWORD dwProcessId,dwThreadId; } PROCESS_INFORMATION;
BOOL CreatePipe(HANDLE*,HANDLE*,SECURITY_ATTRIBUTES*,DWORD);
BOOL SetHandleInformation(HANDLE,DWORD,DWORD);
BOOL CreateProcessW(const WCHAR*,WCHAR*,void*,void*,BOOL,DWORD,void*,const WCHAR*,STARTUPINFOW*,PROCESS_INFORMATION*);
BOOL PeekNamedPipe(HANDLE,void*,DWORD,DWORD*,DWORD*,DWORD*);
BOOL ReadFile(HANDLE,void*,DWORD,DWORD*,void*);
BOOL WriteFile(HANDLE,const void*,DWORD,DWORD*,void*);
BOOL CloseHandle(HANDLE);
BOOL DeleteFileW(const WCHAR*);
DWORD WaitForSingleObject(HANDLE,DWORD);
BOOL TerminateProcess(HANDLE,unsigned int);
DWORD GetLastError(void);
int MultiByteToWideChar(unsigned int,DWORD,const char*,int,WCHAR*,int);
HANDLE CreateFileW(const WCHAR*,DWORD,DWORD,SECURITY_ATTRIBUTES*,DWORD,DWORD,HANDLE);
DWORD GetTempPathW(DWORD,WCHAR*);
unsigned int GetTempFileNameW(const WCHAR*,const WCHAR*,unsigned int,WCHAR*);
HANDLE CreateJobObjectW(void*,const WCHAR*);
BOOL SetInformationJobObject(HANDLE,int,void*,DWORD);
BOOL AssignProcessToJobObject(HANDLE,HANDLE);
DWORD ResumeThread(HANDLE);
]]
local win=ffi.load('kernel32')
local windows={}
local methods={}
methods.__index=methods
local function wide(text)
    local n=win.MultiByteToWideChar(65001,0,text,#text,nil,0)
    assert(n>0,'Invalid Windows path')
    local result=ffi.new('WCHAR[?]',n+1)
    win.MultiByteToWideChar(65001,0,text,#text,result,n)
    return result
end
local function quote(text)
    -- Follow CreateProcess's backslash and quote rules, including trailing slashes.
    return '"'..text:gsub('(\\*)"',function(slashes)return slashes..slashes..'\\"'end):gsub('(\\+)$','%1%1')..'"'
end
local function close(handle)
    if handle~=nil and handle~=ffi.cast('HANDLE',-1) then win.CloseHandle(handle) end
end
function windows.new(python,script)
    local self=setmetatable({},methods)
    local inherited=ffi.new('SECURITY_ATTRIBUTES')
    inherited.nLength=ffi.sizeof(inherited)
    inherited.bInheritHandle=1
    local input_read,input_write=ffi.new('HANDLE[1]'),ffi.new('HANDLE[1]')
    local output_read,output_write=ffi.new('HANDLE[1]'),ffi.new('HANDLE[1]')
    local ok,problem=pcall(function()
        assert(win.CreatePipe(input_read,input_write,inherited,65536)~=0,'Cannot create input pipe')
        self.input=input_write[0]
        assert(win.CreatePipe(output_read,output_write,inherited,65536)~=0,'Cannot create output pipe')
        self.output=output_read[0]
        assert(win.SetHandleInformation(self.input,1,0)~=0,'Cannot protect input handle')
        assert(win.SetHandleInformation(self.output,1,0)~=0,'Cannot protect output handle')
        local temporary,path=ffi.new('WCHAR[32768]'),ffi.new('WCHAR[32768]')
        assert(win.GetTempPathW(32768,temporary)>0,'Cannot find temporary directory')
        assert(win.GetTempFileNameW(temporary,wide('msc'),0,path)~=0,'Cannot create backend log')
        self.log_path=path
        self.log=win.CreateFileW(path,0x40000000,3,inherited,2,0,nil)
        assert(self.log~=ffi.cast('HANDLE',-1),'Cannot open backend log')
        local startup=ffi.new('STARTUPINFOW')
        startup.cb=ffi.sizeof(startup);startup.dwFlags=0x100
        startup.hStdInput=input_read[0];startup.hStdOutput=output_write[0];startup.hStdError=self.log
        local process=ffi.new('PROCESS_INFORMATION')
        -- A job closes every descendant if the window disappears unexpectedly.
        self.job=win.CreateJobObjectW(nil,nil)
        assert(self.job~=nil,'Cannot create backend job')
        local limits=ffi.new('unsigned char[144]')
        ffi.cast('DWORD*',limits+16)[0]=0x2000
        assert(win.SetInformationJobObject(self.job,9,limits,144)~=0,'Cannot configure backend job')
        local command=wide(quote(python)..' '..quote(script)..' --api')
        assert(win.CreateProcessW(wide(python),command,nil,nil,1,0x08000004,nil,nil,startup,process)~=0,
               'Cannot start Python: Windows error '..tonumber(win.GetLastError()))
        self.process=process.hProcess
        local assigned=win.AssignProcessToJobObject(self.job,self.process)~=0
        if assigned then
            assert(tonumber(win.ResumeThread(process.hThread))~=4294967295,'Cannot resume Python')
        else win.TerminateProcess(self.process,1) end
        close(process.hThread)
        assert(assigned,'Cannot attach Python to the player job')
    end)
    close(input_read[0]);close(output_write[0]);close(self.log);self.log=nil
    if not ok then self:close();error(problem) end
    return self
end
function methods:write(data)
    local count=ffi.new('DWORD[1]')
    if win.WriteFile(self.input,data,#data,count,nil)==0 then
        local code=tonumber(win.GetLastError())
        if code==232 and win.WaitForSingleObject(self.process,0)~=0 then return 0 end
        error('Python disconnected while sending (Windows error '..code..')')
    end
    return tonumber(count[0])
end
function methods:read(buffer,size)
    local available=ffi.new('DWORD[1]')
    if win.PeekNamedPipe(self.output,nil,0,nil,available,nil)==0 then
        if tonumber(win.GetLastError())==232 and win.WaitForSingleObject(self.process,0)~=0 then return -1 end
        return 0
    end
    if available[0]==0 then
        if win.WaitForSingleObject(self.process,0)==0 then return 0 end
        return -1
    end
    local count=ffi.new('DWORD[1]')
    if win.ReadFile(self.output,buffer,math.min(size,tonumber(available[0])),count,nil)==0 then return 0 end
    return tonumber(count[0])
end
function methods:close()
    close(self.input);self.input=nil
    if self.process then win.WaitForSingleObject(self.process,5000) end
    close(self.job);self.job=nil
    close(self.output);self.output=nil
    close(self.process);self.process=nil
    if self.log_path then win.DeleteFileW(self.log_path);self.log_path=nil end
end
return windows
