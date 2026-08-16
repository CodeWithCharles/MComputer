local args = { ... }
if #args == 0 then
    write('usage: mkdir <path>')
    return
end

local path = resolve(args[1])
-- The component builds the branch; the store makes one level at a time. False
-- means the leaf was already there, or a segment of the path is a file.
if not invoke(fs, 'makeDirectory', path) then
    write('mkdir: ' .. path .. ': not created')
end
