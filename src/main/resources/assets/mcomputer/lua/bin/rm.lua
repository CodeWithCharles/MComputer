local args = { ... }
if #args == 0 then
    write('usage: rm <path>')
    return
end

local path = resolve(args[1])
-- Recursive: the store removes one entry and refuses a directory with
-- children, the component walks it. There is no confirmation, as in the two
-- mods this one follows.
if not invoke(fs, 'remove', path) then
  write('rm: ' .. path .. ': not removed')
end
