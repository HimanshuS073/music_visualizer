-- SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
-- SPDX-License-Identifier: GPL-3.0-or-later

-- Press F5 in the player after changing these settings.
return {
    window = {
        width = 1080, height = 680,
        max_fps = 60, idle_fps = 30, unfocused_fps = 30, minimized_fps = 5,
    },
    bars = {
        enabled = true,
        count = 100,
        width = 4,
        gap = 10,              -- Minimum gap when fill_width is on.
        fill_width = true,     -- Fit the bars across the window.
        bottom_gap = 0,        -- Gap below the bars, in pixels.
        height = 500,
        min_height = 0,
        radius = 5,
        opacity = 0.5,
        update_rate = 30,       -- Audio readings per second.
        fft_size = 1024,        -- Samples: 256, 512, 1024, 2048 or 4096.
        sample_rate = 44100,
        min_frequency = 100,
        max_frequency = 10000,
        min_db = -65,
        max_db = -8,
        gain = 1,
        attack = 40,           -- Higher makes bars rise faster.
        release = 5,           -- Higher makes bars fall faster.
        palette_size = 8,      -- Colors to pick from each frame.
        color_source = "video", -- "video" or "thumbnail".
        video_color_fps = 8,   -- Video samples per second (1–30).
        color_mode = "per_song", -- "random" uses the timer; "per_song" follows palette changes.
        color_change_speed = 10, -- Changes per second in random mode (0.1–144).
    },
    background = {
        enabled = true,
        max_width = 1280,      -- Smaller uses less GPU memory for the blur.
        blur_radius = 5,
        contrast = 0.4,
        saturation = 0.3,
        brightness = 0.50,
    },
}
