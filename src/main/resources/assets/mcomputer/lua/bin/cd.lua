local args = { ... }
local path = resolve(args[1] or '/')

if not invoke(fs, 'isDirectory', path) then
  write('cd: ' .. path .. ': not a directory')
  return
end

-- cwd is the shell's own global and this command shares it, which is what the
-- globals-as-API call buys. resolve has already made it canonical, so cd ..
-- shortens the prompt instead of lengthening it.
cwd = path
