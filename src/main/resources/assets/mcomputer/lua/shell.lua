-- The MComputer shell. Runs as the boot chunk.
--
-- Everything it draws goes through gpu.write and gpu.set, never print: print
-- reaches the screen a tick later, through ScreenOutput's queue, so a prompt
-- asking where the cursor is would race the drain. print stays what it was
-- built for, the channel a failure reaches the player on.
--
-- gpu, fs, write and invoke are deliberately global: they are this layer's API,
-- and a line typed at the prompt is compiled against these globals.

for address, kind in pairs(component.list()) do
  if kind == 'gpu' then gpu = address
  elseif kind == 'filesystem' then fs = address
  end
end

function invoke(address, method, ...)
  return component.invoke(address, method, ...)
end

local width, height = invoke(gpu, 'getResolution')

function write(text)
  invoke(gpu, 'write', text)
end

local KEY_ENTER = 257
local KEY_BACKSPACE = 259

-- Two key_down arrive for one printable key, one carrying the code and one the
-- character, because GLFW separates them. So a code of zero is not a key.
local function readLine(prompt)
  local line = ''
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
  while true do
    local name, address, char, code = computer.pullSignal()
    if name == 'key_down' then
      if code == KEY_ENTER then
        write(prompt .. line)
        return line
      elseif code == KEY_BACKSPACE then
        line = string.sub(line, 1, #line - 1)
        draw()
      elseif char >= 32 and char <= 126 then
        line = line .. string.char(char)
        draw()
      end
    end
  end
end

-- pcall hands back the whole traceback, five rows for one error on a screen
-- twenty-five tall. This is the caller that knows what to show, so it keeps the
-- first line.
local function firstLine(message)
  return string.match(tostring(message), '^[^\n]*')
end

local function run(source, name)
  local chunk, why = load(source, name)
  if not chunk then
    write(firstLine(why))
    return
  end
  local ok, result = pcall(chunk)
  if not ok then
    write(firstLine(result))
  elseif result ~= nil then
    write(tostring(result))
  end
end

local function readFile(path)
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

write('MComputer ready, ' .. width .. 'x' .. height)

while true do
  local line = readLine('> ')
  if line ~= '' then
    local path = '/' .. line
    if invoke(fs, 'exists', path) and not invoke(fs, 'isDirectory', path) then
      run(readFile(path), line)
    else
      run(line, 'shell')
    end
  end
end