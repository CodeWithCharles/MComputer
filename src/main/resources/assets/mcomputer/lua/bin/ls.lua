local args = { ... }
local path = resolve(args[1] or '.')

if not invoke(fs, 'isDirectory', path) then
  write('ls: ' .. path .. ': not a directory')
  return
end

-- The store answers bare names and the filesystem component adds the slash on
-- a directory, so nothing here has to ask what each one is.
local names = invoke(fs, 'list', path)
local line = ''
for i = 1, #names do
    if line == '' then
        line = names[i]
    elseif #line + 1 + #names[i] <= width then
        line = line .. '  ' .. names[i]
    else
        write(line)
        line = names[i]
    end
end
if line ~= '' then
  write(line)
end
