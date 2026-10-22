function love.load()
    love.graphics.setBackgroundColor(0.1, 0.12, 0.18)
end

function love.update(dt)
    if love.keyboard.isDown("q") then
        love.event.quit()
    end
end

function love.draw()
    love.graphics.setColor(1, 1, 1)
end
