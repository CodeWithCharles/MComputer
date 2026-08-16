-- The Lua interpreter, as a command. With a file name it runs that file; with
-- nothing it reads lines and runs each as its own chunk, which is what typing
-- lua at a shell gives you anywhere else.
--
-- A line is its own chunk, so a local does not survive to the next one.
-- Globals do. That is what load per line means, and what a real REPL does.

local args = { ... }

if #args > 0 then
  local path = resolve(args[1])
  if not invoke(fs, 'exists', path) then
    path = path .. '.lua'
  end
  run(path, table.unpack(args, 2))
  return
end

-- No LuaJ version number here: this file cannot see the build, so a pinned one
-- would be a claim that goes stale at the next bump. _VERSION is the
-- interpreter's own word, and the fallback covers a sandbox that never set it.
write((_VERSION or 'Lua') .. [[ on LuaJ
Enter a statement and hit enter to evaluate it.
Prefix an expression with '=' to show its value.
Type exit to leave the interpreter.]])

while true do
  local line = readLine('lua> ')
  if line == 'exit' then
    return
  end
  if line ~= '' then
    local source = line
    -- What makes the line above true. execute already shows a non-nil result,
    -- so this is sugar for typing return.
    if string.sub(source, 1, 1) == '=' then
      source = 'return ' .. string.sub(source, 2)
    end
    execute(source, 'lua')
  end
end