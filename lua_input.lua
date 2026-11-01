-- Enter a song name here. Python does the search and returns JSON.
-- Run: lua lua_input.lua [python_executable]

local python = arg[1] or "python3"
local folder = arg[0]:match("^(.*[/\\])") or "./"
local script = folder .. "music_call_just_yt_dlp.py"

-- Quote each argument so names containing spaces or quotes stay unchanged.
local function quote(text)
    assert(not text:find("%z"), "Input cannot contain a zero byte")
    return "'" .. text:gsub("'", "'\\''") .. "'"
end

local function search_song(name)
    local command = quote(python) .. " " .. quote(script) .. " --search " .. quote(name)
    local process, error_message = io.popen(command, "r")
    if not process then
        return nil, error_message
    end
    local result = process:read("*a")
    process:close()
    if result == "" then
        return nil, "Python returned no JSON. Check the Python path and dependencies."
    end
    return result
end

while true do
    io.write("Song name (q to quit): ")
    io.flush()
    local name = io.read("*l")
    if not name then break end
    name = name:match("^%s*(.-)%s*$")
    if name:lower() == "q" then break end
    if name ~= "" then
        local result, error_message = search_song(name)
        if result then
            io.write(result)
        else
            io.stderr:write(error_message .. "\n")
        end
    end
end
