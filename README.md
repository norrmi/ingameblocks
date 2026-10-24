# InGameBlocks (Fabric, Minecraft 26.3)

Make your own blocks in-game: a picture, a name, a block type and a sound. Blocks are stored on your own computer (`.minecraft/ingameblocks/`), and you can share them with other players who have the mod on the same server.

## Usage
* **B** — open the menu (configurable in Controls). Tabs: **My blocks** (your blocks) and **Shared blocks** (blocks other players share). Your shared blocks are highlighted in blue.
* Drag PNG, JPG or **WebP** pictures into the window (16, 32, 64, 128 or 256 px; non-square pictures are cropped to the centre), or exported `.zip` blocks.
* Click to select a block. Double-click or right-click your own block to rename it. Shift+click takes a stack. **Edit** opens the editor: name, id, **type** (full block, glass, slab, stairs, fence, carpet, plant/flower, head, chest, anvil, piston), **sound** of any vanilla block (default: the sound of the type), sharing, and **a separate picture for each side**.
* **Paint** is a built-in texture editor: brush (size, opacity, softness, round or square tip, mirror X), eraser, fill, colour picker (or right-click), line, rectangle (Shift for filled), undo/redo (Ctrl+Z / Ctrl+Y), zoom with the mouse wheel, pan with the middle button. You can repaint your own, vanilla and other players' textures via **Choose…** (tabs: Mine / Minecraft / Shared).
* **Export** saves a block as a `.zip` (in the `ingameblocks/exports` folder) that a friend can drop into their menu.
* Your blocks keep their identity (a `uid` stored in `library.json`), so you can keep editing them after reconnecting, and editing does not create duplicates.

## Commands
`/igb` (same as `/ingameblocks`): `menu`, `folder`, `reload`, `list`, `give <id> [amount]`, `share <id> on|off`, `rename <id> <name>`, `export <id>`, `import <file>`.
Admins: `/igbadmin list`, `/igbadmin free <type> <number>`.

## How it works
Minecraft cannot add new blocks while the game is running, so the mod has one block per type (`ingameblocks:cube`, `ingameblocks:slab`, ...), and the specific picture is stored in a `design` property. Slots are assigned as blocks are added, so there is no fixed list of 999 reserved IDs. The resource pack with the textures is built in memory and is enabled **only on servers that have the mod**; it is listed as "InGameBlocks (Server-side)".

Limitations:
* Piston is decorative and does not push blocks.
* Blocks from the old CustomBlocks version that are already placed in a world are not converted. Their pictures are copied automatically.
* Blocks face the player when placed; pillars follow the direction you look.
