# RainbowLightning

Paper plugin for Minecraft 1.21.11.

## How to use
- `/rainbowrod` - get the Rainbow Storm Rod (op only)
- `/rainbowrod <player>` - give it to someone
- `/rainbowrod size <0.25-10>` - make the lightning bigger or smaller (op only, saved to config)
- `/rainbowrod reload` - reload `config.yml`

Hold right-click with the rod. After 1 second of charging, a rainbow lightning beam
fires wherever you aim. The bolt is built from glowing, full-bright rainbow blocks
that cycle colors as it crackles. Everything it touches takes rapid armor-ignoring damage
(totems still pop), and the ground it hits gets burned and charred.

## Building
GitHub Actions builds it automatically on every push.
Go to the **Actions** tab -> latest run -> download the **RainbowLightning** artifact
(it's a zip containing the .jar). Put the .jar in your server's `plugins` folder and restart.

## Permissions
- `rainbowlightning.give` - get/give the rod (default: op)
- `rainbowlightning.size` - change lightning size (default: op)
- `rainbowlightning.reload` - reload config (default: op)
- `rainbowlightning.use` - fire the beam (default: everyone)
