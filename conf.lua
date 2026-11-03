-- SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
-- SPDX-License-Identifier: GPL-3.0-or-later

-- mpv handles sound. These unused LÖVE modules do not need to start.
function love.conf(t)
    t.modules.audio = false
    t.modules.sound = false
    t.modules.physics = false
    t.modules.joystick = false
    t.modules.video = false
    t.window.resizable = true
end
