# MCImage

A [Paper](https://papermc.io) **26.3** plugin that turns a base64-encoded image into a
map-art item. Every image gets a short, shareable ID, and anyone can grab a copy of it
with `/mcimage get <id>`.

```
/mcimage iVBORw0KGgoAAAANSUhEUgAA...     ->  a filled map showing the picture, ID #k3x9ab
/mcimage get k3x9ab                      ->  another copy of that map
```

MCImage is purely an item renderer: **it never places blocks, never touches terrain and
never writes into chunks**. The picture lives in `plugins/MCImage/images/<id>.png` and is
drawn onto a locked map item by a custom renderer.

---

## Features

- **`/mcimage <base64>`** - decode a PNG / JPEG / GIF / BMP / WebP\* image and hand the
  player a 128x128 map of it (`*` WebP needs an ImageIO WebP plugin on the server;
  PNG, JPEG, GIF and BMP work out of the box).
- **Short IDs** - every image gets a 6-character ID such as `k3x9ab` (confusable characters
  like `0/o/1/l` are never used). Chat messages contain click-to-copy / click-to-suggest
  links, and the ID is also written on the map item (name, lore and persistent data).
- **`/mcimage get <id>`** - a copy for yourself, or for another player / from the console.
- **Browser uploads** - `/mcimage upload` gives the player a temporary, single-use browser
  link where they can choose an image file instead of pasting Base64 (requires an HTTPS
  reverse proxy; see [Browser upload](#browser-upload)).
- **Multi-part uploads** - the vanilla chat box only sends 256 characters per command, so
  longer images are pasted in several parts and finished with `/mcimage done`. The plugin
  detects partial pastes automatically; no special syntax is needed.
- **Encoder tool** - `tools/encoder.html` is an offline web page that resizes a picture to
  map size, previews it with the real Minecraft map palette and generates the ready-to-paste
  commands (with a "copy next" button that keeps track of your progress).
- **Good looking maps** - high-quality downscaling, `fit` / `crop` / `stretch` modes,
  transparency support and Floyd-Steinberg dithering on the map palette.
- **Persistent** - images survive restarts, map re-loads and even the deletion of the
  world's `map_<n>.dat` files; renderers are re-attached automatically.
- **Admin extras** - `/mcimage url <link>` (SSRF-safe downloader), `/mcimage file <name>`
  (from `plugins/MCImage/import/`), listing/deleting other players' images, reload.
- **Safe by default** - size limits before decoding (decompression-bomb protection),
  per-player cooldown and image quota, all heavy work (decoding, resizing, disk I/O) runs
  off the main thread, atomic saves, fully configurable MiniMessage texts.

---

## Requirements

| | |
|---|---|
| Server | Paper 26.3 (or a fork of it) |
| Java | 25 |
| Dependencies | none |

---

## Installation

1. Build the jar (see [Building](#building)) or take `MCImage-1.0.0.jar` from a release.
2. Drop it into your server's `plugins/` folder and (re)start the server.
3. Optional: edit `plugins/MCImage/config.yml` and run `/mcimage reload`.

Players can create images straight away (`mcimage.use` / `mcimage.create` default to
`true`); `url`, `file`, `reload` and the admin listing are op-only.

---

## Using it

### Small images (one command)

Encode the image file as base64 (the [encoder tool](#the-encoder-tool), `base64 -w0 img.png`,
or any online converter) and send it:

```
/mcimage iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==
```

`data:image/png;base64,` prefixes, quotes, whitespace and URL-safe base64 are all accepted.

### Bigger images (several commands)

The vanilla client sends at most **256 characters** per chat message, which is only about
185 bytes of image data - too little for anything but a tiny icon. Longer images are
therefore pasted in parts:

```
/mcimage begin                 (optional - discards a previous unfinished upload)
/mcimage /9j/4AAQSkZJRgABAQ...  part 1
/mcimage AAAAAAAAD/2wBDAAYE...  part 2
...                             (each part at most 247 characters)
/mcimage done
```

You do not have to type `begin`: if a paste is chat-sized and is not a complete image file,
MCImage starts an upload for you and tells you so. While an upload is open **every**
`/mcimage <text>` is treated as the next part. `/mcimage status` shows how many parts have
arrived, `/mcimage cancel` throws them away, and an upload that receives nothing for ten
minutes (configurable) expires on its own.

The **encoder tool** produces exactly these commands, so in practice it is: open the tool,
drop the picture in, click *Copy next*, paste in chat, repeat.

A typical 128x128 JPEG is 4-8 KB, i.e. 25-45 chat messages at quality 0.75; use `crop`,
lower the JPEG quality or simpler pictures if you want fewer parts. Console, RCON, command
blocks and clients with a raised chat limit can send the whole thing in one command (the
server accepts up to 32,767 characters).

### Browser upload

Players can avoid Base64 entirely with `/mcimage upload`. MCImage sends a clickable link
that opens a file picker in the browser; after upload, the plugin runs the same image
validation and processing pipeline and gives the player a map. Links are random, single-use,
expire after ten minutes by default, and are invalidated when another link is requested or
the player disconnects. The upload size and image dimensions are still capped by server
settings.

The embedded listener speaks HTTP and binds to `127.0.0.1:8765` by default. **For public
use, put it behind an HTTPS reverse proxy** and configure the public base URL. Example
`plugins/MCImage/config.yml` settings:

```yaml
upload:
  enabled: true
  bind-address: "127.0.0.1"
  port: 8765
  public-url: "https://upload.example.com"
  token-lifetime-seconds: 600
  max-bytes: 5000000
```

Configure your proxy's `https://upload.example.com` virtual host to forward requests to
`http://127.0.0.1:8765` while preserving the request path. Keep the HTTP listener private;
do not expose the bearer-token upload endpoint directly over plain HTTP. After configuring
the proxy, run `/mcimage reload` or restart the server. Browser upload appears in
`/mcimage help` only when the listener starts successfully. Set `upload.enabled: false`
to disable it.

### The encoder tool

Open `tools/encoder.html` in any browser (double-click it; nothing is uploaded anywhere).
It lets you

- pick / drop / paste an image,
- choose `fit`, `crop` or `stretch`, the output format and the JPEG quality,
- preview the result **as it will look on the map** (real palette, dithering),
- copy the commands one by one, or the single long command for console use,
- download the 128x128 file for `/mcimage file`.

### Getting copies

```
/mcimage get k3x9ab            a copy for yourself
/mcimage get k3x9ab Alex       give it to Alex (needs mcimage.admin, works from the console)
/mcimage info                  show the ID of the map you are holding
/mcimage list [page]           your images; admins: /mcimage list all|<player> [page]
/mcimage delete k3x9ab         delete your image (admins: anyone's) - existing maps go blank
```

Players can of course also copy maps the vanilla way (map + empty map in a crafting grid)
or put them in item frames; copies keep rendering the image.

---

## Commands

Aliases: `/mcimg`, `/imagemap`.

| Command | Permission | Description |
|---|---|---|
| `/mcimage <base64>` | `mcimage.create` | Create a map from base64 image data (or add a part to the current upload) |
| `/mcimage create <base64>` | `mcimage.create` | Same, explicit form |
| `/mcimage begin` | `mcimage.create` | Start a multi-part upload (optional, resets a previous one) |
| `/mcimage done` | `mcimage.create` | Finish the upload and create the map |
| `/mcimage cancel` | `mcimage.create` | Discard the upload in progress |
| `/mcimage status` | `mcimage.create` | Show how much of the upload has arrived |
| `/mcimage get <id> [player]` | `mcimage.get` | Get a copy of an image (`[player]` and console use need `mcimage.admin`) |
| `/mcimage info` | `mcimage.use` | Show the ID, owner and details of the map you are holding |
| `/mcimage list [page]` | `mcimage.list` | List your images (admins: `list all [page]` / `list <player> [page]`) |
| `/mcimage delete <id>` | `mcimage.delete` | Delete one of your images (admins: any image) |
| `/mcimage url <link>` | `mcimage.url` (op) | Create a map from an `http(s)` image URL |
| `/mcimage file <name>` | `mcimage.file` (op) | Create a map from `plugins/MCImage/import/<name>` |
| `/mcimage reload` | `mcimage.admin` (op) | Reload `config.yml` (settings and messages) |
| `/mcimage help` | `mcimage.use` | Show the help |

`/mcimage done`, `begin` etc. have the aliases `finish`/`end`, `start`, `abort`, `remove`/`del`,
`add`/`new`. The console can create images too (the ID is printed; no item is given).

### Permissions

| Permission | Default | Grants |
|---|---|---|
| `mcimage.use` | everyone | Base permission for the command, `help`, `info` |
| `mcimage.create` | everyone | Creating images from base64 |
| `mcimage.get` | everyone | `/mcimage get <id>` for yourself |
| `mcimage.list` | everyone | Listing your own images |
| `mcimage.delete` | everyone | Deleting your own images |
| `mcimage.url` | op | `/mcimage url` |
| `mcimage.file` | op | `/mcimage file` |
| `mcimage.admin` | op | `get` for others / from console, `list all` / `list <player>`, deleting any image, `reload`, bypasses cooldown and quota |
| `mcimage.*` | op | Everything above |

---

## Configuration

`plugins/MCImage/config.yml` (reload with `/mcimage reload`):

| Key | Default | Meaning |
|---|---|---|
| `id-length` | `6` | Length of generated IDs (4-32). 6 characters give ~900 million IDs. |
| `cooldown-seconds` | `5` | Seconds between two creations per player (admins exempt). |
| `max-images-per-player` | `100` | Quota per player, `0` = unlimited (admins exempt). |
| `max-base64-length` | `1000000` | Longest accepted base64 text, multi-part uploads included (~750 KB of data). |
| `max-image-dimension` | `4096` | Images wider/taller than this are rejected *before* decoding. |
| `max-image-pixels` | `16777216` | Same for the total pixel count (16 MP). |
| `max-file-bytes` | `20000000` | Size limit for `/mcimage file`. |
| `upload-session-timeout-seconds` | `600` | An unfinished multi-part upload is dropped after this much inactivity. |
| `resize-mode` | `fit` | `fit` (keep aspect ratio, bars at the sides), `stretch`, or `crop`. |
| `background` | `transparent` | Fill for transparent pixels / `fit` bars: `transparent` (map paper shows through) or a hex colour like `#ffffff`. |
| `dithering` | `true` | Floyd-Steinberg dithering on the map palette (much smoother photos; turn off for flat pixel art). |
| `preload-maps-on-startup` | `true` | Re-attach all image renderers at startup instead of lazily on first use. |
| `list-page-size` | `8` | Entries per `/mcimage list` page. |
| `url.enabled` | `true` | Master switch for `/mcimage url`. |
| `url.timeout-seconds` | `10` | Connect/read timeout. |
| `url.max-bytes` | `5000000` | Download limit (checked against `Content-Length` *and* while streaming). |
| `url.allow-private-addresses` | `false` | Allow links that resolve to loopback / LAN / link-local addresses (keep `false` unless you know why). |
| `url.allowed-hosts` | `[]` | If set, only these hosts and their sub-domains may be fetched, e.g. `[imgur.com]`. |
| `url.user-agent` | `MCImage/1.0 (Paper plugin)` | Sent with downloads. |

### Messages

Every text the plugin sends lives under `messages:` in
[MiniMessage](https://docs.papermc.io/adventure/minimessage/format/) format. Delete a line to
fall back to the built-in default. `<prefix>` is available everywhere. The other placeholders:

| Message | Placeholders |
|---|---|
| `player-not-found` | `<name>` |
| `unknown-image`, `created-no-item`, `given`, `deleted`, `not-owner`, `item-name` | `<id>` |
| `invalid-id`, `unknown-command` | `<input>` |
| `cooldown` | `<seconds>` |
| `limit-reached` | `<count>`, `<limit>` |
| `created` | `<id>`, `<width>`, `<height>`, `<format>` |
| `given-other` | `<id>`, `<player>` |
| `received` | `<id>`, `<sender>` |
| `too-long` | `<length>`, `<limit>` |
| `error-unsupported-format`, `error-too-large`, `error-corrupt`, `error-download` | `<detail>` |
| `file-not-found` | `<name>` |
| `upload-started` | `<chars>` |
| `upload-part`, `upload-status` | `<part>`, `<chars>` |
| `info` | `<id>`, `<owner>`, `<width>`, `<height>`, `<date>`, `<source>` |
| `list-header` | `<scope>`, `<page>`, `<pages>`, `<total>` |
| `list-entry` | `<id>`, `<owner>`, `<date>`, `<source>` |
| `list-footer` | `<next-command>` |
| `help-header` | `<version>` |
| `help-line` | `<command>`, `<description>` |
| `item-lore` | `<id>`, `<owner>` |

`<id>`, numbers and `<next-command>` are inserted before parsing, so they can be used inside
tags, e.g. `<click:suggest_command:'/mcimage get <id>'>`. Player-provided text (`<owner>`,
`<input>`, `<detail>`, ...) is always inserted literally and cannot inject formatting.

---

## How it works / safety notes

- **Nothing is written to your worlds.** Creating a map item makes Paper allocate a map ID,
  which (like a vanilla empty map) stores a small `map_<n>.dat` file plus `idcounts.dat` in
  the main world's `data/` folder. That is the only world-folder footprint; the plugin never
  places blocks, entities or modifies chunks. Maps are **locked**, so the server never tries
  to scan terrain for them, and position tracking is off.
- **Image data** is stored as a 128x128 PNG in `plugins/MCImage/images/<id>.png`; metadata
  (owner, map ID, date, source, original size) in `plugins/MCImage/images.yml`. Back up that
  folder and your images survive anything. If a `map_<n>.dat` file disappears, the next
  `/mcimage get` recreates the map and updates the record.
- **Rendering**: a custom `MapRenderer` paints the PNG onto the map canvas once per map and
  server session (converting to the map palette with optional dithering); afterwards Paper
  just re-sends the cached canvas. Clients receive ordinary map packets - no client mod needed.
- **Untrusted input**: image headers are inspected before decoding so oversized images are
  refused without allocating memory; decoding, resizing and disk writes run asynchronously;
  base64/text limits, cooldowns and quotas keep spam in check; URL downloads only allow
  `http(s)`, follow at most 5 redirects, re-check every hop against the private-address and
  host allow-lists and are capped in size. File imports are confined to the `import/` folder.
- **Chat log**: like every command, the base64 parts appear in the server console/log
  (`<player> issued server command: /mcimage ...`). If that bothers you, Spigot's
  `commands.log` setting in `spigot.yml` controls command logging.

---

## Building

```
./gradlew build          # -> build/libs/MCImage-1.0.0.jar
./gradlew runServer      # downloads Paper 26.3 into ./run and starts it with the plugin
```

Requires JDK 25 (the Gradle toolchain downloads one if needed). The project has no
runtime dependencies - everything it uses ships with Paper.

Project layout:

```
src/main/java/io/github/wowsha/mcimage/
  MCImagePlugin          plugin entry point, events, scheduling
  command/               /mcimage command + tab completion
  image/                 base64 -> decoded, resized 128x128 image (ImageProcessor, ImageCreator)
  map/                   map renderer, canvas painter (palette + dithering), MapArtService
  store/                 images.yml + PNG storage
  upload/                multi-part upload sessions
  net/                   hardened URL downloader
  text/                  MiniMessage texts
  config/                typed settings
tools/encoder.html       browser-side image -> commands converter
```

---

## FAQ

**Why only 128x128?** One map is 128x128 pixels. Multi-map walls are a planned extension;
for now use `crop` or `fit`.

**My map shows the picture only after I move / re-open it.** Map updates are sent by the
server a few times per second while you hold the map; a freshly given map can take a moment.

**Do copies made with a crafting table work?** Yes - they share the map ID, so they render
the same image. Deleting the image (`/mcimage delete`) blanks all of them.

**Can I use `/mcimage` from a command block?** Yes; command blocks accept 32,500 characters,
which is enough for a complete image in one command.

**Chat says "Unknown sub command" when I paste.** Anything shorter than 32 characters is
treated as a mistyped sub command rather than image data. Real base64 image data is always
longer than that.
