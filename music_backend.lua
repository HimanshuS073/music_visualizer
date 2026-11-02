-- Send JSON requests to Python and read replies without stopping the window.
local ffi = require("ffi")
local AF_UNIX, SOCK_STREAM = 1, 1
local NONBLOCKING, NO_SIGNAL, TRY_AGAIN = 0x40, 0x4000, 11
ffi.cdef[[
int socketpair(int domain, int type, int protocol, int sv[2]);
int fork(void); int dup2(int oldfd, int newfd); int execvp(const char *file, char *const argv[]);
int close(int fd); int shutdown(int fd, int how); int waitpid(int pid, int *status, int options);
long send(int fd, const void *buf, unsigned long len, int flags);
long recv(int fd, void *buf, unsigned long len, int flags);
void _exit(int status);
]]

local backend = {}
local client = {}
client.__index = client

local function quote(text)
    return '"' .. text:gsub('[%z\1-\31\\"]', function(char)
        if char == '"' then return '\\"' end
        if char == '\\' then return '\\\\' end
        return string.format('\\u%04x', char:byte())
    end) .. '"'
end

local function utf8_char(n)
    if n < 128 then return string.char(n) end
    if n < 2048 then return string.char(192 + math.floor(n / 64), 128 + n % 64) end
    if n < 65536 then
        return string.char(224 + math.floor(n / 4096), 128 + math.floor(n / 64) % 64, 128 + n % 64)
    end
    return string.char(240 + math.floor(n / 262144), 128 + math.floor(n / 4096) % 64,
                       128 + math.floor(n / 64) % 64, 128 + n % 64)
end

-- Python sends normal JSON. This reader handles strings, arrays and objects.
function backend.decode(text)
    local position = 1
    local parse
    local function space()
        local _, finish = text:find("^%s*", position)
        position = (finish or position - 1) + 1
    end
    local function read_string()
        position = position + 1
        local parts = {}
        while position <= #text do
            local special = text:find('["\\%z\1-\31]', position)
            assert(special, "Unfinished JSON string")
            parts[#parts + 1] = text:sub(position, special - 1)
            local char = text:sub(special, special)
            position = special + 1
            if char == '"' then return table.concat(parts) end
            assert(char == '\\', "Invalid JSON string")
            local escape = text:sub(position, position)
            position = position + 1
            if escape == 'u' then
                local hex = text:sub(position, position + 3)
                assert(hex:match('^%x%x%x%x$'), 'Invalid Unicode escape')
                local code = tonumber(hex, 16)
                position = position + 4
                if code >= 0xD800 and code <= 0xDBFF then
                    assert(text:sub(position, position + 1) == '\\u', 'Missing Unicode surrogate')
                    local low = tonumber(text:sub(position + 2, position + 5), 16)
                    assert(low and low >= 0xDC00 and low <= 0xDFFF, 'Invalid Unicode surrogate')
                    code = 0x10000 + (code - 0xD800) * 1024 + low - 0xDC00
                    position = position + 6
                else
                    assert(code < 0xDC00 or code > 0xDFFF, 'Invalid Unicode surrogate')
                end
                parts[#parts + 1] = utf8_char(code)
            else
                local escapes = {['"']='"', ['\\']='\\', ['/']='/', b='\b', f='\f', n='\n', r='\r', t='\t'}
                assert(escapes[escape], 'Invalid JSON escape')
                parts[#parts + 1] = escapes[escape]
            end
        end
        error('Unfinished JSON string')
    end
    parse = function(depth)
        assert(depth < 64, 'JSON is nested too deeply')
        space()
        local char = text:sub(position, position)
        if char == '"' then return read_string() end
        if char == '{' or char == '[' then
            local object = char == '{'
            local closing = object and '}' or ']'
            local value, index = {}, 1
            position = position + 1
            space()
            if text:sub(position, position) == closing then position = position + 1; return value end
            while true do
                local key = index
                if object then
                    space()
                    assert(text:sub(position, position) == '"', 'Expected JSON key')
                    key = read_string()
                    space()
                    assert(text:sub(position, position) == ':', 'Expected colon')
                    position = position + 1
                end
                value[key] = parse(depth + 1)
                index = index + 1
                space()
                local separator = text:sub(position, position)
                position = position + 1
                if separator == closing then return value end
                assert(separator == ',', 'Expected comma')
            end
        end
        for word, value in pairs({['true']=true, ['false']=false}) do
            if text:sub(position, position + #word - 1) == word then
                position = position + #word
                return value
            end
        end
        if text:sub(position, position + 3) == 'null' then position = position + 4; return nil end
        local number = text:match('^-?%d+%.?%d*[eE]?[+-]?%d*', position)
        assert(number and tonumber(number), 'Invalid JSON value')
        position = position + #number
        return tonumber(number)
    end
    local value = parse(0)
    space()
    assert(position > #text, 'Unexpected text after JSON')
    return value
end

function backend.new(python, script)
    if ffi.os == "Windows" then
        return setmetatable({fd=true, windows=require("music_windows").new(python,script),
                             next_id=0,input='',output='',buffer=ffi.new('char[16384]')},client)
    end
    local sockets = ffi.new('int[2]')
    assert(ffi.C.socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0, 'Cannot create Python connection')
    local strings = {ffi.new('char[?]', #python + 1, python),
                     ffi.new('char[?]', #script + 1, script), ffi.new('char[6]', '--api')}
    local args = ffi.new('char *[4]')
    for i = 1, 3 do args[i - 1] = strings[i] end
    local pid = ffi.C.fork()
    if pid < 0 then
        ffi.C.close(sockets[0]); ffi.C.close(sockets[1]); error('Cannot start Python')
    end
    if pid == 0 then
        ffi.C.close(sockets[0])
        if ffi.C.dup2(sockets[1], 0) < 0 or ffi.C.dup2(sockets[1], 1) < 0 then ffi.C._exit(127) end
        if sockets[1] > 1 then ffi.C.close(sockets[1]) end
        ffi.C.execvp(args[0], args)
        ffi.C._exit(127)
    end
    ffi.C.close(sockets[1])
    return setmetatable({fd=tonumber(sockets[0]), pid=tonumber(pid), next_id=0,
                         input='', output='', buffer=ffi.new('char[16384]')}, client)
end

function client:send(command, fields)
    assert(self.fd, 'Python is disconnected')
    self.next_id = self.next_id + 1
    local parts = {'"id":' .. self.next_id, '"command":' .. quote(command)}
    for key, value in pairs(fields or {}) do
        local encoded
        if type(value) == 'string' then encoded = quote(value)
        elseif type(value) == 'number' and value == value and math.abs(value) < math.huge then
            encoded = tostring(value)
        elseif type(value) == 'boolean' then encoded = tostring(value)
        else error('Unsupported request value') end
        parts[#parts + 1] = quote(key) .. ':' .. encoded
    end
    self.output = self.output .. '{' .. table.concat(parts, ',') .. '}\n'
    self:flush()
    return self.next_id
end

function client:flush()
    -- Send now when possible; leave queued bytes alone if the socket is busy.
    while #self.output > 0 do
        local sent
        if self.windows then sent=self.windows:write(self.output)
        else sent=tonumber(ffi.C.send(self.fd,self.output,#self.output,NONBLOCKING+NO_SIGNAL)) end
        if sent < 0 then
            if self.windows or ffi.errno() == TRY_AGAIN then break end
            self:close(); error('Python disconnected while sending')
        end
        if sent == 0 then break end
        self.output = self.output:sub(sent + 1)
    end
end

function client:poll()
    if not self.fd then return {} end
    self:flush()
    local replies = {}
    local buffer = self.buffer
    -- Limit each frame's work when a large thumbnail is arriving.
    for _ = 1, 32 do
        local count
        if self.windows then count=self.windows:read(buffer,16384)
        else count=tonumber(ffi.C.recv(self.fd,buffer,16384,NONBLOCKING)) end
        if count < 0 then
            if self.windows or ffi.errno() == TRY_AGAIN then break end
            self:close(); error('Cannot read Python reply')
        end
        if count == 0 then self:close(); error('Python process exited') end
        self.input = self.input .. ffi.string(buffer, count)
        while true do
            local newline = self.input:find('\n', 1, true)
            if not newline then break end
            local line = self.input:sub(1, newline - 1)
            self.input = self.input:sub(newline + 1)
            replies[#replies + 1] = backend.decode(line)
        end
    end
    return replies
end

function client:close()
    if not self.fd then return end
    if self.windows then
        self.windows:close();self.windows=nil;self.fd=nil;return
    end
    ffi.C.shutdown(self.fd, 1)
    ffi.C.close(self.fd)
    self.fd = nil
    local status = ffi.new('int[1]')
    while ffi.C.waitpid(self.pid, status, 0) < 0 do
        if ffi.errno() ~= 4 then break end
    end
end

return backend
