-- What a visitor types first. One write, so the whole page lands in one tick.

write([[
Commands. Type one and press enter.

  ls [path]        what is in a folder
  cd <path>        go into a folder, cd .. goes back up
  cat <path>       show a file
  mkdir <path>     make a folder
  rm <path>        delete a file or a whole folder
  mv <from> <to>   rename or move something
  lua              a Lua prompt, type exit to leave
  glyphs           every character this screen can draw
  hi               say hello
  help             this

A name with no slash is looked for here first, then in /bin, and .lua is
added when the name alone finds nothing.]])
