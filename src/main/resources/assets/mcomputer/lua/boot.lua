-- The MComputer shell. Installed as /boot.lua and run by the boot chunk, which
-- is this system's BIOS: it finds a filesystem, reads this file, runs it.
--
-- It dispatches. A line is a command word and its arguments, the word names a
-- file, and running that file is all this loop does. lua is one of those files.
--
-- Everything it draws goes through gpu.write and gpu.set, never print: print
-- reaches the screen a tick later, through ScreenOutput's queue, so a prompt
-- asking where the cursor is would race the drain. print stays what it was
-- built for, the channel a failure reaches the player on.
--
-- The globals below are this layer's API, and only they are. A command is
-- compiled with no environment of its own, so it sees them and calls them like
-- this file does. There is no require: the sandbox installs no package library.

for address, kind in pairs(component.list()) do
  if kind == 'gpu' then gpu = address
  elseif kind == 'filesystem' then fs = address
  end
end

function invoke(address, method, ...)
  return component.invoke(address, method, ...)
end

width, height = invoke(gpu, 'getResolution')

function write(text)
  invoke(gpu, 'write', text)
end

-- Where the shell stands. cd is the command that moves it.
cwd = '/'

-- Searched in order for a bare word. The current directory first, which is what
-- a player who just wrote a script expects; the reason Unix puts it last is a
-- multi-user concern that does not exist here.
PATH = { '.', '/bin' }

-- Relative to cwd unless it opens with a slash, then normalised by the store,
-- which is the only reader of a path in this system.
function resolve(path)
  if string.sub(path, 1, 1) ~= '/' then
    path = cwd .. '/' .. path
  end
  return invoke(fs, 'canonical', path)
end

local KEY_ENTER = 257
local KEY_BACKSPACE = 259

-- Two key_down arrive for one printable key, one carrying the code and one the
-- character, because GLFW separates them. So a code of zero is not a key.
function readLine(prompt)
  local line = ''
  -- write is the only thing that scrolls, so the prompt claims its row through
  -- it and set only ever redraws a row that is already the buffer's. Painting
  -- the row a write is about to scroll into left a copy of the prompt behind
  -- and ate the last line of every command's output.
  write(prompt)
  local row = invoke(gpu, 'getCursor')
  local function draw()
    local text = prompt .. line
    -- Longer than the row: show its tail, so what is being typed stays in
    -- sight. Wrapping would want free rows below the prompt and a way to
    -- scroll, which the card does not have.
    if #text > width then
      text = string.sub(text, #text - width + 1)
    end
    invoke(gpu, 'set', 1, row,
      string.sub(text .. string.rep(' ', width), 1, width))
  end
  draw()
  -- One gpu.set per burst of keys rather than one per key. A component call
  -- crosses to the server thread and waits for the next tick, so a draw per key
  -- makes a fast typist queue behind his own echo, each character paying a tick
  -- the one before it has not finished. A signal pull does not cross, so
  -- emptying the queue first is free.
  local name, address, char, code = computer.pullSignal()
  while true do
    if name == 'key_down' then
      if code == KEY_ENTER then
        -- The row already holds the line, drawn in place, and writing it again
        -- would land on the next row. Anything typed after the enter stays in
        -- the queue and the next readLine gets it.
        return line
      elseif code == KEY_BACKSPACE then
        line = string.sub(line, 1, #line - 1)
      elseif char >= 32 and char <= 126 then
        line = line .. string.char(char)
      end
    end
    name, address, char, code = computer.pullSignal(0)
    if name == nil then
      draw()
      name, address, char, code = computer.pullSignal()
    end
  end
end

-- pcall hands back the whole traceback, five rows for one error on a screen
-- twenty-five tall. This is the caller that knows what to show.
function firstLine(message)
  return string.match(tostring(message), '^[^\n]*')
end

function readFile(path)
  local handle = invoke(fs, 'open', path, 'r')
  local source = ''
  while true do
    local piece = invoke(fs, 'read', handle, 2048)
    if piece == nil then break end
    source = source .. piece
  end
  invoke(fs, 'close', handle)
  return source
end

function execute(source, name, ...)
  local chunk, why = load(source, name)
  if not chunk then
    write(firstLine(why))
    return
  end
  local ok, result = pcall(chunk, ...)
  if not ok then
    write(firstLine(result))
  elseif result ~= nil then
    write(tostring(result))
  end
end

-- The one place this system decides what a program is. Handing a file to
-- another engine instead of to load is a change here and nowhere else.
function run(path, ...)
  execute(readFile(path), path, ...)
end

local function readable(path)
  if invoke(fs, 'exists', path) and not invoke(fs, 'isDirectory', path) then
    return path
  end
  return nil
end

-- The name as typed, then the name with .lua. Both mods this one follows do it,
-- so ls and ls.lua both reach /bin/ls.lua.
local function candidate(path)
  return readable(path) or readable(path .. '.lua')
end

-- A word carrying a slash is a path and PATH is not consulted, as in a real
-- shell. Otherwise the search list decides.
local function locate(name)
  if string.find(name, '/', 1, true) then
    return candidate(resolve(name))
  end
  for i = 1, #PATH do
    local found = candidate(resolve(PATH[i] .. '/' .. name))
    if found then
      return found
    end
  end
  return nil
end

local function words(line)
  local out = {}
  -- Not %S: the readline accepts 32 to 126, so a tab cannot be typed, and this
  -- pattern uses only the complement class firstLine already proves works.
  for word in string.gmatch(line, '[^ ]+') do
    out[#out + 1] = word
  end
  return out
end

write('MComputer ready, ' .. width .. 'x' .. height)

while true do
  local parsed = words(readLine(cwd .. '> '))
  if #parsed > 0 then
    local name = table.remove(parsed, 1)
    local path = locate(name)
    if path then
      run(path, table.unpack(parsed))
    else
      write(name .. ': command not found')
    end
  end
end