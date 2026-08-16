-- Every byte this screen can draw. A byte is a code point in the terminal
-- font's own range, so all 256 have a glyph and none is unprintable.
--
-- Byte 10 is the exception, and not because the font lacks it: it is the one
-- byte writeLine reads instead of storing. It cannot cross inside a string, so
-- the grid goes out with a space in its place and set puts the glyph back. set
-- reads no byte at all, which is the counterweight to writeLine reading that
-- one.

local LABEL = 5

local rows = {}
for row = 0, 15 do
  local line = string.format('%4d ', row * 16)
  for column = 0, 15 do
    local byte = row * 16 + column
    line = line .. (byte == 10 and ' ' or string.char(byte)) .. ' '
  end
  rows[#rows + 1] = line
end
write(table.concat(rows, '\n'))

-- getCursor is the row write last used, so the grid starts fifteen above it.
-- Guarded because a screen that scrolled may have taken the first row with it.
local top = invoke(gpu, 'getCursor') - 15
if top >= 1 then
  invoke(gpu, 'set', LABEL + 10 * 2 + 1, top, string.char(10))
end
