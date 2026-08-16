local args = { ... }
if #args == 0 then
    write('usage: cat <path>')
    return
end

local path = resolve(args[1])
if not invoke(fs, 'exists', path) or invoke(fs, 'isDirectory', path) then
    write('cat: ' .. path .. ': no such file')
    return
end

-- One write for the whole file: gpu.write reads '\n' and lays out the rows.
write(readFile(path))
