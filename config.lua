-- Change these values, then press F5 in the player to reload them.
return {
    window = {
        width = 1080, height = 680,
        max_fps = 60, idle_fps = 30, unfocused_fps = 30, minimized_fps = 5,
    },
    bars = {
        enabled = true,
        count = 100,
        width = 4,
        gap = 10,               -- Minimum spacing when fill_width is enabled.
        fill_width = true,      -- Spread the bars across the window.
        bottom_gap = 0,        -- Space between the bars and the controls.
        height = 500,
        min_height = 0,
        radius = 1,
        opacity = 0.5,
        update_rate = 60,       -- Target audio readings per second.
        fft_size = 2048,        -- 256, 512, 1024, 2048, or 4096 samples.
        sample_rate = 44100,
        min_frequency = 40,
        max_frequency = 10000,
        min_db = -65,
        max_db = -8,
        gain = 1,
        attack = 40,            -- Higher values follow new sounds faster.
        release = 5,          -- Higher values let bars fall faster.
        palette_size = 8,       -- Number of colors picked from the current frame.
        color_source = "video", -- "video" follows actual video frames; "thumbnail" uses the cover.
        video_color_fps = 8,    -- Small frame samples per second (1–30).
        color_mode = "random", -- Shuffle on a timer; "per_song" changes only with the palette.
        color_change_speed = 10, -- Bar color changes per second (0.1–144). Lower is slower.
    },
    background = {
        enabled = true,
        max_width = 1280,      -- Limit the cached blur's size to save GPU memory.
        blur_radius = 5,
        contrast = 0.4,
        saturation = 0.3,
        brightness = 0.50,
    },
}
