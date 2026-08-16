local args = { ... }
if #args < 2 then
    write('usage: mv <from> <to>')
    return
end

local from = resolve(args[1])
local to = resolve(args[2])
-- False when the source names nothing, when the target exists, when its parent
-- is missing, or when the target sits under the source.
if not invoke(fs, 'rename', from, to) then
  write('mv: ' .. from .. ' to ' .. to .. ': not moved')
end
