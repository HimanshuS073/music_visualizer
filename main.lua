local utf8 = require("utf8")
local music_backend = require("music_backend")
local Spectrum = require("spectrum")
local config = require("config")

song_name = ""
selected_song = nil

local backend
local pending = {}
local panel_open = false
local input_focused = false
local input_text = ""
local results = {}
local selected_result = 1
local first_result = 1
local search_message = "Type a song name and press Enter."
local player_message = "Choose something to listen to."
local cover_message = ""
local latest_search, latest_play, latest_image
local status_request
local status_timer = 0
local paused = false
local position, duration = 0, nil
local cover_image, background, blur_shader
local fonts = {}
local visualizer = Spectrum.new(config.bars or {})
local spectrum_request, spectrum_timer = nil, 0
local video_palette_request, video_palette_timer = nil, 0
local playback_running = false
local background_dirty = false
local last_background_update = 0
local RESULT_HEIGHT = 64
local RESULTS_TOP = 128

local function bounded(value, fallback, low, high)
    value = tonumber(value)
    if not value or value ~= value then value = fallback end
    return math.max(low, math.min(high, value))
end

local function release(object)
    if object then object:release() end
end

local function visualizer_layout()
    local w, h = love.graphics.getDimensions()
    local baseline = h - 84 - visualizer.settings.bottom_gap
    local height = 0
    if selected_song and visualizer.settings.enabled then
        height = math.min(visualizer.settings.height, math.max(32, baseline - 300))
    end
    return {x=24, width=w - 48, top=baseline - height, baseline=baseline, height=height}
end

local function panel_layout()
    local w = love.graphics.getWidth()
    local width = math.min(440, w - 32)
    local bottom = visualizer_layout().top - 16
    return {x=w - width - 20, y=70, width=width, height=math.max(208, bottom - 70)}
end

local function visible_rows()
    return math.max(1, math.floor((panel_layout().height - RESULTS_TOP - 12) / RESULT_HEIGHT))
end

local function keep_selection_visible()
    local rows = visible_rows()
    if selected_result < first_result then first_result = selected_result end
    if selected_result >= first_result + rows then first_result = selected_result - rows + 1 end
    first_result = math.max(1, math.min(first_result, math.max(1, #results - rows + 1)))
end

local function set_input_focus(value)
    input_focused = value
    love.keyboard.setTextInput(value)
end

function open_input_box()
    panel_open = true
    input_text = ""
    set_input_focus(true)
end

local function close_input_box()
    panel_open = false
    set_input_focus(false)
end

local function request(command, fields, kind)
    if not backend or not backend.fd then
        player_message = "Python is not connected. Check the terminal for errors."
        return nil
    end
    local ok, id = pcall(backend.send, backend, command, fields)
    if not ok then player_message = tostring(id); return nil end
    pending[id] = kind or command
    return id
end

local function submit_search()
    local name = input_text:match("^%s*(.-)%s*$")
    if name == "" then return end
    song_name = name
    results = {}
    selected_result, first_result = 1, 1
    search_message = "Searching for “" .. name .. "”…"
    set_input_focus(false)
    latest_search = request("search", {query=name}, "search")
    if not latest_search then search_message = "Could not start the search." end
end

local function rebuild_background()
    background_dirty = false
    last_background_update = love.timer.getTime()
    release(background)
    background = nil
    local settings = config.background or {}
    if not cover_image or settings.enabled == false then return end
    local screen_w, screen_h = love.graphics.getDimensions()
    local limit = bounded(settings.max_width, 1280, 320, 2560)
    local ratio = math.min(1, limit / screen_w)
    local w, h = math.max(1, math.floor(screen_w * ratio)), math.max(1, math.floor(screen_h * ratio))
    local first = love.graphics.newCanvas(w, h, {dpiscale=1})
    local second
    love.graphics.push("all")
    love.graphics.setCanvas(first)
    love.graphics.clear(0.07, 0.08, 0.11, 1)
    love.graphics.setShader()
    love.graphics.setColor(1, 1, 1)
    local iw, ih = cover_image:getDimensions()
    local scale = math.max(w / iw, h / ih)
    love.graphics.draw(cover_image, (w - iw * scale) / 2, (h - ih * scale) / 2, 0, scale, scale)
    if blur_shader then
        second = love.graphics.newCanvas(w, h, {dpiscale=1})
        love.graphics.setCanvas(second)
        love.graphics.clear()
        love.graphics.setShader(blur_shader)
        local radius = bounded(settings.blur_radius, 3, 0, 12)
        blur_shader:send("contrast", bounded(settings.contrast, .4, 0, 1))
        blur_shader:send("saturation", bounded(settings.saturation, .3, 0, 1))
        blur_shader:send("brightness", bounded(settings.brightness, .45, .05, 1))
        blur_shader:send("stepSize", {radius / w, 0})
        blur_shader:send("reduceContrast", 0)
        love.graphics.draw(first)
        love.graphics.setCanvas(first)
        love.graphics.clear()
        blur_shader:send("stepSize", {0, radius / h})
        blur_shader:send("reduceContrast", 1)
        love.graphics.draw(second)
    else
        love.graphics.setColor(0.07, 0.08, 0.1, 0.8)
        love.graphics.rectangle("fill", 0, 0, w, h)
    end
    love.graphics.setCanvas()
    love.graphics.pop()
    release(second)
    background = first
end

local function select_song(index)
    local song = results[index]
    if not song then return end
    selected_result = index
    selected_song = song
    position, duration, paused = 0, song.duration, false
    playback_running = true
    visualizer:reset(song.video_id)
    spectrum_request, spectrum_timer = nil, 0
    video_palette_request, video_palette_timer = nil, 0
    player_message = "Loading audio…"
    cover_message = "Loading cover…"
    release(cover_image); cover_image = nil
    release(background); background = nil
    latest_play = request("play", {video_id=song.video_id, spectrum=visualizer.settings.enabled,
                                  video_colors=visualizer.settings.enabled and visualizer.settings.color_source == "video",
                                  video_color_fps=visualizer.settings.video_color_fps,
                                  fft_size=visualizer.settings.fft_size,
                                  sample_rate=visualizer.settings.sample_rate}, "play")
    latest_image = request("image", {video_id=song.video_id}, "image")
    close_input_box()
end

local function receive(reply)
    local kind = pending[reply.id]
    pending[reply.id] = nil
    if kind == "search" then
        if reply.id ~= latest_search then return end
        if not reply.ok then search_message = reply.error; return end
        results = reply.results or {}
        selected_result, first_result = 1, 1
        search_message = #results == 0 and "No songs found. Try another name." or
                         tostring(#results) .. " results · click a song or use Up/Down and Enter"
    elseif kind == "play" then
        if reply.id ~= latest_play then return end
        player_message = reply.ok and "Loading audio…" or reply.error
        if reply.ok then status_timer = .75 else playback_running = false end
    elseif kind == "image" then
        if reply.id ~= latest_image then return end
        if not reply.ok then cover_message = "Cover unavailable: " .. tostring(reply.error); return end
        if not selected_song or reply.video_id ~= selected_song.video_id then return end
        local ok, problem = pcall(function()
            local bytes = love.data.decode("string", "base64", reply.image)
            local file = love.filesystem.newFileData(bytes, "cover.jpg")
            local pixels = love.image.newImageData(file)
            if visualizer.settings.color_source == "thumbnail" then
                visualizer:set_palette(pixels, reply.video_id)
            end
            local image = love.graphics.newImage(pixels)
            pixels:release()
            file:release()
            image:setFilter("linear", "linear")
            release(cover_image)
            cover_image = image
            rebuild_background()
        end)
        cover_message = ok and "" or "Could not draw cover: " .. tostring(problem)
    elseif kind == "video_palette" then
        if reply.id ~= video_palette_request then return end
        video_palette_request = nil
        if reply.ok and selected_song and reply.video_id == selected_song.video_id
           and visualizer.settings.color_source == "video" then
            visualizer:set_video_palette(reply)
        end
    elseif kind == "spectrum" then
        if reply.id ~= spectrum_request then return end
        spectrum_request = nil
        if reply.ok then
            visualizer:feed(reply)
            if reply.available and not paused then player_message = "Playing" end
        else visualizer:feed({available=false, reason=reply.error}) end
    elseif kind == "status" then
        status_request = nil
        if not reply.ok then return end
        -- A status reply queued before a new selection must not describe the old track.
        if not selected_song or not reply.song or reply.song.video_id ~= selected_song.video_id then return end
        paused = reply.paused or false
        playback_running = reply.running or false
        position = reply.position or 0
        duration = reply.duration or selected_song.duration
        if reply.error then player_message = reply.error
        elseif reply.ready then player_message = paused and "Paused" or "Playing"
        elseif not reply.running then player_message = "Playback ended"
        else player_message = "Loading audio…" end
    elseif kind == "toggle" then
        if reply.ok then
            paused = reply.message == "Paused."
            player_message = paused and "Paused" or "Playing"
            status_timer = .75
        else player_message = reply.error end
    elseif kind == "forward" or kind == "backward" or kind == "seek" then
        if reply.ok then status_timer = .75 else player_message = reply.error end
    elseif not reply.ok then
        player_message = reply.error
    end
end

function love.load()
    love.window.setTitle("Music room")
    local window = config.window or {}
    love.window.setMode(bounded(window.width, 1080, 720, 3840), bounded(window.height, 680, 480, 2160),
                       {resizable=true, minwidth=720, minheight=480, highdpi=true, vsync=1})
    love.graphics.setBackgroundColor(0.045, 0.055, 0.075)
    love.graphics.setDefaultFilter("linear", "linear")
    fonts.title = love.graphics.newFont(25)
    fonts.body = love.graphics.newFont(14)
    fonts.small = love.graphics.newFont(12)
    local ok, shader = pcall(love.graphics.newShader, [[
        extern vec2 stepSize;
        extern number reduceContrast;
        extern number contrast;
        extern number saturation;
        extern number brightness;
        vec4 effect(vec4 color, Image image, vec2 uv, vec2 screen) {
            vec4 sum = Texel(image, uv) * 0.227027;
            sum += Texel(image, uv + stepSize * 1.0) * 0.194595;
            sum += Texel(image, uv - stepSize * 1.0) * 0.194595;
            sum += Texel(image, uv + stepSize * 2.0) * 0.121622;
            sum += Texel(image, uv - stepSize * 2.0) * 0.121622;
            sum += Texel(image, uv + stepSize * 3.0) * 0.054054;
            sum += Texel(image, uv - stepSize * 3.0) * 0.054054;
            sum += Texel(image, uv + stepSize * 4.0) * 0.016216;
            sum += Texel(image, uv - stepSize * 4.0) * 0.016216;
            number gray = dot(sum.rgb, vec3(0.299, 0.587, 0.114));
            vec3 muted = mix(vec3(gray), sum.rgb, saturation);
            muted = ((muted - vec3(0.5)) * contrast + vec3(0.5)) * brightness;
            return vec4(mix(sum.rgb, muted, reduceContrast), 1.0) * color;
        }
    ]])
    if ok then blur_shader = shader else cover_message = "Blur unavailable: " .. tostring(shader) end
    local source = love.filesystem.getSource()
    local started, connection = pcall(music_backend.new, os.getenv("MUSIC_PYTHON") or "python3",
                                      source .. "/music_call_just_yt_dlp.py")
    if started then backend = connection else player_message = tostring(connection) end
end

function love.update(dt)
    if backend and backend.fd then
        local ok, replies = pcall(backend.poll, backend)
        if ok then
            for _, reply in ipairs(replies) do receive(reply) end
        else
            player_message = "Python connection failed: " .. tostring(replies)
            search_message = "Search connection is unavailable."
        end
    end
    status_timer = status_timer + dt
    if selected_song and not status_request and status_timer >= 0.75 then
        status_timer = 0
        status_request = request("status", nil, "status")
    end
    spectrum_timer = spectrum_timer + dt
    local rate = visualizer.settings.update_rate
    if visualizer.reason then rate = math.min(rate, 1) end
    if not love.window.hasFocus() then rate = math.min(rate, 30) end
    if selected_song and playback_running and not paused and visualizer.settings.enabled and not spectrum_request
       and love.graphics.isActive() and spectrum_timer >= 1 / rate then
        -- Keep the fractional time so a 48 Hz reading rate can work at 60 FPS.
        spectrum_timer = math.min(spectrum_timer - 1 / rate, 1 / rate)
        spectrum_request = request("spectrum", {sequence=visualizer.sequence}, "spectrum")
    end
    video_palette_timer = video_palette_timer + dt
    local color_rate = visualizer.settings.video_color_fps
    if not love.window.hasFocus() then color_rate = math.min(color_rate, 4) end
    if selected_song and playback_running and not paused and visualizer.settings.enabled
       and visualizer.settings.color_source == "video" and not video_palette_request
       and love.graphics.isActive() and video_palette_timer >= 1 / color_rate then
        video_palette_timer = math.min(video_palette_timer - 1 / color_rate, 1 / color_rate)
        video_palette_request = request("video_palette", {sequence=visualizer.frame_sequence}, "video_palette")
    end
    visualizer:update(dt, playback_running and not paused)
    if background_dirty and love.timer.getTime() - last_background_update >= .075 then
        rebuild_background()
    end
end

local function reload_config()
    local previous = package.loaded.config
    package.loaded.config = nil
    local ok, settings = pcall(require, "config")
    if not ok or type(settings) ~= "table" then
        package.loaded.config = previous
        player_message = "Config error: " .. tostring(settings)
        return
    end
    config = settings
    visualizer:configure(config.bars or {})
    keep_selection_visible()
    rebuild_background()
    spectrum_request = nil
    if selected_song and playback_running then
        if visualizer.settings.enabled then
            request("spectrum_start", {fft_size=visualizer.settings.fft_size,
                                      sample_rate=visualizer.settings.sample_rate})
        else request("spectrum_stop") end
    end
    player_message = "Config reloaded."
end

function love.keyreleased(key)
    -- Open on release so the opening S is not inserted into the search field.
    if key == "s" and not input_focused then open_input_box() end
end

function love.textinput(text)
    if input_focused then input_text = input_text .. text end
end

function love.keypressed(key)
    if key == "f5" then reload_config(); return end
    if key == "escape" then close_input_box(); return end
    if input_focused then
        if key == "return" or key == "kpenter" then submit_search()
        elseif key == "backspace" then
            local offset = utf8.offset(input_text, -1)
            if offset then input_text = input_text:sub(1, offset - 1) end
        elseif key == "tab" then set_input_focus(false) end
        return
    end
    if panel_open and (key == "up" or key == "down") then
        if #results > 0 then
            local step = key == "down" and 1 or -1
            selected_result = math.max(1, math.min(#results, selected_result + step))
            keep_selection_visible()
        end
    elseif panel_open and (key == "return" or key == "kpenter") then select_song(selected_result)
    elseif key == "space" and selected_song then request("toggle")
    elseif key == "right" and selected_song then request("forward", {seconds=10})
    elseif key == "left" and selected_song then request("backward", {seconds=10})
    elseif key == "q" then love.event.quit() end
end

local function inside(x, y, left, top, width, height)
    return x >= left and x <= left + width and y >= top and y <= top + height
end

function love.mousepressed(x, y, button)
    if button ~= 1 then return end
    local w, h = love.graphics.getDimensions()
    if inside(x, y, w - 130, 18, 110, 34) then open_input_box(); return end
    if selected_song and y >= h - 66 and y <= h - 22 then
        if inside(x, y, w / 2 - 116, h - 66, 66, 44) then request("backward", {seconds=10}); return end
        if inside(x, y, w / 2 - 42, h - 66, 84, 44) then request("toggle"); return end
        if inside(x, y, w / 2 + 50, h - 66, 66, 44) then request("forward", {seconds=10}); return end
    end
    if not panel_open then return end
    local p = panel_layout()
    if inside(x, y, p.x + 14, p.y + 48, p.width - 108, 40) then set_input_focus(true); return end
    if inside(x, y, p.x + p.width - 86, p.y + 48, 72, 40) then submit_search(); return end
    local list_y = p.y + RESULTS_TOP
    if inside(x, y, p.x + 10, list_y, p.width - 20, visible_rows() * RESULT_HEIGHT) then
        local index = first_result + math.floor((y - list_y) / RESULT_HEIGHT)
        if results[index] then select_song(index) end
    elseif not inside(x, y, p.x, p.y, p.width, p.height) then close_input_box() end
end

function love.wheelmoved(_, amount)
    if not panel_open or amount == 0 then return end
    local x, y = love.mouse.getPosition()
    local p = panel_layout()
    if inside(x, y, p.x, p.y, p.width, p.height) then
        local step = amount > 0 and math.max(1, math.floor(amount)) or math.min(-1, math.ceil(amount))
        first_result = math.max(1, math.min(math.max(1, #results - visible_rows() + 1), first_result - step))
    end
end

function love.resize()
    keep_selection_visible()
    -- Reuse the cached image while dragging the window; rebuild after a short delay.
    background_dirty = true
end

local function time_text(seconds)
    if not seconds then return "--:--" end
    return string.format("%d:%02d", math.floor(seconds / 60), math.floor(seconds % 60))
end

local function fitted(text, width, font)
    text = tostring(text or "")
    if font:getWidth(text) <= width then return text end
    while #text > 0 and font:getWidth(text .. "…") > width do
        local offset = utf8.offset(text, -1)
        text = text:sub(1, (offset or 1) - 1)
    end
    return text .. "…"
end

local function button(text, x, y, width, height)
    love.graphics.setColor(0.13, 0.16, 0.21, 0.97)
    love.graphics.rectangle("fill", x, y, width, height, 6)
    love.graphics.setColor(0.88, 0.91, 0.95)
    love.graphics.setFont(fonts.body)
    love.graphics.printf(text, x, y + (height - fonts.body:getHeight()) / 2, width, "center")
end

function draw_input_box()
    if not panel_open then return end
    local p = panel_layout()
    love.graphics.setColor(0.055, 0.07, 0.10, 0.96)
    love.graphics.rectangle("fill", p.x, p.y, p.width, p.height, 10)
    love.graphics.setFont(fonts.body)
    love.graphics.setColor(0.9, 0.94, 0.97)
    love.graphics.print("Find a song", p.x + 16, p.y + 17)
    love.graphics.setColor(0.025, 0.035, 0.055)
    love.graphics.rectangle("fill", p.x + 14, p.y + 48, p.width - 108, 40, 5)
    love.graphics.setColor(input_focused and 0.3 or 0.15, 0.5, 0.5, 0.8)
    love.graphics.rectangle("line", p.x + 14, p.y + 48, p.width - 108, 40, 5)
    love.graphics.setScissor(p.x + 22, p.y + 51, p.width - 124, 34)
    local text = input_text
    if input_focused then text = text .. "|" end
    if text == "" then text = "Type another song name…" end
    local scroll = math.max(0, fonts.body:getWidth(text) - (p.width - 126))
    love.graphics.setColor(0.88, 0.91, 0.95)
    love.graphics.print(text, p.x + 22 - scroll, p.y + 60)
    love.graphics.setScissor()
    button("Search", p.x + p.width - 86, p.y + 48, 72, 40)
    love.graphics.setFont(fonts.small)
    love.graphics.setColor(0.57, 0.66, 0.73)
    love.graphics.printf(search_message, p.x + 16, p.y + 99, p.width - 32)
    local list_y = p.y + RESULTS_TOP
    for row = 0, visible_rows() - 1 do
        local index = first_result + row
        local song = results[index]
        if not song then break end
        local y = list_y + row * RESULT_HEIGHT
        if index == selected_result then
            love.graphics.setColor(0.12, 0.30, 0.30, 0.95)
            love.graphics.rectangle("fill", p.x + 10, y, p.width - 20, RESULT_HEIGHT - 4, 5)
        end
        love.graphics.setColor(0.63, 0.73, 0.77)
        love.graphics.setFont(fonts.small)
        love.graphics.print(string.format("%02d", index), p.x + 18, y + 12)
        love.graphics.setFont(fonts.body)
        love.graphics.setColor(0.91, 0.94, 0.96)
        local _, lines = fonts.body:getWrap(song.title, p.width - 80)
        love.graphics.print(lines[1] or song.title, p.x + 48, y + 8)
        if lines[2] then love.graphics.print(fitted(lines[2], p.width - 80, fonts.body), p.x + 48, y + 25) end
        love.graphics.setFont(fonts.small)
        love.graphics.setColor(0.59, 0.68, 0.73)
        love.graphics.print(fitted(song.channel or "Unknown channel", p.width - 126, fonts.small), p.x + 48, y + 46)
        love.graphics.printf(time_text(song.duration), p.x + p.width - 74, y + 46, 50, "right")
    end
end

local function draw_background()
    if not background then return end
    local w, h = love.graphics.getDimensions()
    love.graphics.setColor(1, 1, 1)
    love.graphics.draw(background, 0, 0, 0, w / background:getWidth(), h / background:getHeight())
end

local function draw_heading()
    local w = love.graphics.getWidth()
    local text_width = panel_open and math.max(180, panel_layout().x - 64) or w - 56
    love.graphics.setFont(fonts.small)
    love.graphics.setColor(0.40, 0.77, 0.73)
    love.graphics.print("MUSIC", 28, 20)
    button("Search · S", w - 130, 18, 110, 34)

    love.graphics.setFont(fonts.title)
    love.graphics.setColor(0.94, 0.96, 0.98)
    local title = selected_song and selected_song.title or "Find a song you love"
    local _, lines = fonts.title:getWrap(title, text_width)
    title = lines[1] or title
    if lines[2] then
        local second = lines[2]
        if lines[3] then second = fitted(second .. " …", text_width, fonts.title) end
        title = title .. "\n" .. second
    end
    love.graphics.printf(title, 28, 115, text_width)
    love.graphics.setFont(fonts.body)
    love.graphics.setColor(0.64, 0.72, 0.77)
    local artist = selected_song and (selected_song.channel or "") or "Press S to search YouTube."
    love.graphics.print(fitted(artist, text_width, fonts.body), 28, 88)

    love.graphics.setFont(fonts.small)
    if cover_message ~= "" then
        love.graphics.print(fitted(cover_message, text_width, fonts.small), 28, 184)
    end
    if visualizer.reason and playback_running and visualizer.settings.enabled then
        love.graphics.print(fitted("FFT: " .. visualizer.reason, text_width, fonts.small), 28, 202)
    end
end

local function draw_visualizer()
    if not selected_song or not visualizer.settings.enabled then return end
    local area = visualizer_layout()
    visualizer:draw(area.x, area.baseline, area.width, area.height)
end

local function draw_controls()
    local w, h = love.graphics.getDimensions()
    love.graphics.setColor(0.045, 0.06, 0.085, 0.96)
    love.graphics.rectangle("fill", 16, h - 84, w - 32, 70, 8)
    love.graphics.setFont(fonts.small)
    love.graphics.setColor(0.8, 0.86, 0.90)
    local title = selected_song and selected_song.title or "Nothing playing"
    local label_width = math.max(90, w / 2 - 170)
    love.graphics.print(fitted(title, label_width, fonts.small), 28, h - 68)
    love.graphics.setColor(0.5, 0.62, 0.69)
    love.graphics.print(fitted(player_message, label_width, fonts.small), 28, h - 45)
    button("-10s", w / 2 - 116, h - 66, 66, 44)
    button(paused and "Play" or "Pause", w / 2 - 42, h - 66, 84, 44)
    button("+10s", w / 2 + 50, h - 66, 66, 44)
    love.graphics.setFont(fonts.small)
    love.graphics.setColor(0.6, 0.7, 0.75)
    love.graphics.printf(time_text(position) .. " / " .. time_text(duration), w - 175, h - 57, 145, "right")
end

local function frame_limit()
    local window = config.window or {}
    local cap = bounded(window.max_fps, 60, 15, 144)
    if not love.graphics.isActive() then
        return bounded(window.minimized_fps, 5, 2, 15)
    end
    if not selected_song or not playback_running or paused then
        cap = math.min(cap, bounded(window.idle_fps, 30, 5, 144))
    end
    if not love.window.hasFocus() then
        local limit = playback_running and not paused and 30 or 12
        cap = math.min(cap, bounded(window.unfocused_fps, 30, 5, limit))
    end
    return cap
end

function love.draw()
    draw_background()
    draw_heading()
    draw_visualizer()
    draw_input_box()
    draw_controls()
end

function love.run()
    if love.load then love.load(love.arg.parseGameArguments(arg), arg) end
    love.timer.step()

    return function()
        local started = love.timer.getTime()
        love.event.pump()
        for event, a, b, c, d, e, f in love.event.poll() do
            if event == "quit" then
                if not love.quit or not love.quit() then return a or 0 end
            else
                love.handlers[event](a, b, c, d, e, f)
            end
        end
        love.update(love.timer.step())
        if love.graphics.isActive() then
            love.graphics.origin()
            love.graphics.clear(love.graphics.getBackgroundColor())
            love.draw()
            love.graphics.present()
        end

        -- Limit after presenting. Sleeping before vsync can skip a whole refresh.
        local remaining = 1 / frame_limit() - (love.timer.getTime() - started)
        if remaining > 0 then love.timer.sleep(remaining) end
    end
end

function love.quit()
    if backend then backend:close(); backend = nil end
    release(cover_image); cover_image = nil
    release(background); background = nil
    release(blur_shader); blur_shader = nil
end
