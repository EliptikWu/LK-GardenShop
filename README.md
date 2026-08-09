# LKGardenShop

Sell *Grow a Garden*-style harvests in Minecraft. Weight, variant and mutations decide
what a crop is worth; the money goes to whatever economy plugin the server already runs,
through Vault.

Built for a MythicMobs + MythicCrucible crop pack that grows six crops in three variants across ten
mutation states — 180 harvest items in all.

**The crops are sold separately.** This is the till, not the produce. The plugin never opens a pack
file — it reads whatever crops MythicMobs has loaded — and the pack it is built and priced for is
**[From Seed to Sky: Ultimate Farming Expansion](https://mcmodels.net/products/16939/from-seed-to-sky-ultimate-farming-expansion)**,
which you buy there. Nothing of it is redistributed here. Install it and the shop has 180 harvest
types to sell; install nothing and the shop still starts, tells you so in one line, and sells
nothing. A different Crucible pack works too: `crops.yml` takes the tokens of *your* pack, and the
shipped one is tuned for the above.

**Support:** [Discord](https://discord.com/invite/ZfCC7amBu7)

---

## What it does

| Command | |
|---|---|
| `/gs` | **Opens the shop menu.** Everything below is also reachable from there. |
| `/gs value` | What is the crop in my hand worth? Shows the full breakdown, sells nothing. |
| `/gs sell hand` | Sell the stack I am holding. |
| `/gs sell all` | Sell every crop in my bag, skipping favorites. |
| `/gs favorite` | Protect the held crop from `/gs sell all`. |
| `/gs prices [crop]` | The whole price sheet — every crop, or every drop type of one. |
| `/gs reload` | Re-read the config files. No restart. |
| `/gs info` | What is loaded, which economy is hooked, what is broken. |

`/gardenshop`, `/gs` and `/gshop` are the same command. `/gs help` lists the admin
commands only for senders who can actually run them.

Those mirror the three options Steven's *Sell Stuff* stand offers in the original game, plus
the favorite flag that keeps a bulk sale from eating a record harvest.

### The menu

`/gs` opens a three-screen GUI. Layout is configurable in [`gui.yml`](gardenshop-bukkit/src/main/resources/gui.yml),
wording in `messages.yml`, and both reload with `/gs reload`.

- **Shop front** — the crop in your hand appraised in full, what the whole bag is worth, your
  lifetime earnings, and buttons for either kind of sale.
- **Confirmation** — shown before a bulk sale, with the count of favorited crops it will leave
  alone. This is the screen that tells a player whether they remembered to protect their best
  harvest. Turn it off with `confirm-bulk-sale: false` if you would rather not have it.
- **Price book** — one icon per crop, click through to all 30 of its drop types with the price
  at its lightest and heaviest. The same figures `/gs prices` prints.

**No menu ever holds a real item.** Every slot is a display icon, and every click and drag inside
the menu is cancelled. Your own inventory stays usable with the shop open — all except the two
actions that reach across the boundary, shift-click and double-click-collect, which would have a
player walking off with a button; anything you are allowed to do down there re-renders the menu,
because it is describing the bag you just changed. A "drop your crops in here" chest would feel more
tactile and would introduce a family of item-loss bugs on close, crash and reload; showing crops as
icons and selling from the real inventory gets the same feel with none of that. Ten tests in
`MenuSafetyTest` hold that line.

Menus are closed on `/gs reload` (the prices they display have just been replaced) and on
plugin disable (a menu whose listener is gone is an unguarded inventory).

### The shop artwork

The shop front is a real texture, not an arrangement of item icons. It lives in
[`resourcepack/`](resourcepack/) and is drawn as a **glyph in the inventory's title** — a title
glyph renders behind the items and in front of the container background, which is exactly the
layer a backdrop needs, and it takes no client mod and no NMS.

```
resourcepack/
  pack.mcmeta
  assets/minecraft/font/gui.json                    negative-space advances + two backdrops
  assets/minecraft/textures/gui/garden_shop_gui.png       the stand, shelves drawn in the panel
  assets/minecraft/textures/gui/garden_shop_gui_list.png  the same, panel left empty
  assets/minecraft/textures/item/gardenshop/              four 16×16 button icons
  assets/minecraft/models/item/name_tag.json              CustomModelData 74001-74004
```

Both backdrops are a 256×256 canvas whose art is **252×239**, and they render 1:1 — which is not a
preference. The art draws its own slot cells, and those cells are **18 px apart**, the vanilla pitch;
scale the glyph at all and every item sits off-centre in the cell drawn for it. Everything else falls
out of measuring where the art puts its grid against where a window puts its slots:

```
drawn columns  x 47, 65, 83 ... 191    window slots start at x 8         -> 39 px left
drawn rows     y 156, 174, 192, 214    player rows 140, 158, 176, 198    -> 16 px up
```

So `ascent: 31` and `background-x-offset: -48` — the measured 29 and −47, each with a pixel or two
asked for on screen. Which is also why **every menu is 6 rows**: the drawn grid is a 6-row chest's.

The icons hang off `name_tag.json` rather than `paper.json`, and that matters: every item in the crop
pack is built on paper, so that pack owns `paper.json` with 144 overrides of its own. Two packs cannot
both own one file.

```powershell
.\gradlew packZip     # zips the pack, prints its SHA-1, embeds it in the plugin jar
```

**The pack is cosmetic.** `resource-pack.installed: false` turns every piece of art off at once and
the menus fall back to a plain chest with a text title — deliberately, because pack art on a player
without the pack is a row of missing-character boxes, not decoration. `menu.style` in `gui.yml`
refines that: `styled` always, `plain` never, `auto` per player based on who actually completed the
download.

**Delivery.** With `resource-pack.url` set the plugin sends that URL; leave it blank and it serves
the zip itself over a small built-in HTTP server. Self-hosting needs a **TCP port open to the
internet**, separate from the Minecraft port — on a managed host that is an extra allocation you
have to request. If you cannot get one, host the zip anywhere and paste the `url` plus the `sha1`
that `packZip` printed.

Tuning: horizontal position is `background-x-offset` in `gui.yml` and takes effect on `/gs reload`.
Vertical position and scale are `ascent` and `height` in the pack's `font/gui.json`, and need a
`packZip` afterwards — it recomputes the hash for you.

Adding art later is a drop-in: put the PNG in the pack and see
[`resourcepack/README.md`](resourcepack/README.md). Button icons already carry the
`material` / `model-data` / `fallback-material` plumbing, so a custom icon is a texture plus a
number — no code.

### Permissions

| Node | Default |
|---|---|
| `lkgardenshop.menu` | everyone |
| `lkgardenshop.sell` | everyone |
| `lkgardenshop.value` | everyone |
| `lkgardenshop.favorite` | everyone |
| `lkgardenshop.admin.reload` | op |
| `lkgardenshop.admin.info` | op |
| `lkgardenshop.admin.prices` | op |
| `lkgardenshop.admin.adapter` | op |
| `lkgardenshop.admin` | op — implies the four admin nodes |

`admin.prices` covers both `/gs prices` and the price-book button inside the menu, so on
the defaults the price sheet is op-only. Grant it to everyone if players should browse it.

---

## Requirements

| | |
|---|---|
| Server | Paper 1.21.3 or newer |
| Java | 21 |
| Required | MythicMobs 5 + MythicCrucible (they run the crops) |
| The crops | **[From Seed to Sky: Ultimate Farming Expansion](https://mcmodels.net/products/16939/from-seed-to-sky-ultimate-farming-expansion)** — bought separately; the pack this ships priced for. Any Crucible pack works once `crops.yml` names its tokens |
| For payouts | Vault **or** VaultUnlocked, plus any economy plugin — EssentialsX, CMI, CoinsEngine, ExcellentEconomy… |
| Optional | PlaceholderAPI |
| Bundled | [ItemBridge](https://github.com/EliptikWu/LK-ItemBridge), shaded in — nothing to install |

Nothing but a broken config file stops the plugin from starting. No MythicMobs and items
are identified from the plugin's own tags. No Vault and the server still boots, prices can
still be inspected with `/gs value` and `/gs prices`, and selling explains why it is off.
No PlaceholderAPI and there are simply no placeholders.

### Crops from other item plugins

Two things bridge to the plugins a server actually runs. **Vault** does it for economies, so a
payout reaches whatever the server pays in; **[ItemBridge](https://github.com/EliptikWu/LK-ItemBridge)**
does it for item identity, giving every custom item an id of the form `plugin:id` across MythicMobs,
Crucible, ItemsAdder, Nexo, Oraxen and CraftEngine.

ItemBridge is ours, bundled and relocated, so there is nothing to install and no third party's
release schedule or licence in the way. It reaches each plugin by reflection, which is why it has no
dependencies of its own and why an item plugin that is not installed costs nothing.

It is the **third** route to identifying a harvest, behind the plugin's own tag and a direct
MythicMobs call, and it covers what those two cannot:

- a MythicMobs API that has started failing;
- crops supplied by a different item plugin, listed under a crop's `extra-ids` in `crops.yml`.

Mythic and Crucible ids need no configuration at all: the part after the prefix *is* the Mythic
type name. The `Item adapter` row on the startup banner says whether it came up.

### Mapping an item from another plugin

The plugin writes this config itself. Hold the item and:

```
/gs adapter hand              every id that item answers to, and what each one does
/gs adapter list [text]       search every id your item plugins know
/gs adapter bind <crop>       sell the held item as that crop's plain drop
/gs adapter unbind            undo it
```

`bind` writes `adapter-bindings.yml` and reloads, so the item is sellable immediately. Needs
`lkgardenshop.admin.adapter`.

What it deliberately does **not** do is guess. Only a person knows that a particular tomato is
meant to be an Odre, and a pack with `carrot_seed`, `carrot_crate` and `golden_carrot_statue`
offers three ways to be wrong about one word — so a wrong guess would pay real money for a
decorative statue. Two guards follow from that:

- **A vanilla id is never bindable.** Our crops are Mythic items built on `Id: paper`, so every
  one of them truthfully answers to `mc:paper`. Binding that would turn every sheet of paper on
  the server into a harvest.
- **More than one candidate id, and it refuses to pick** — it lists them and waits for
  `/gs adapter bind <crop> <id>`.

A bound item sells as the crop's **plain** drop: normal variant, no mutations. An item from
another plugin carries no variant or mutation information, and inventing some would price it as
something it is not.

### Vault or VaultUnlocked

Either works, and the same economy plugins sit behind both. On `AUTO` the plugin prefers
**VaultUnlocked** when it is installed, for two concrete reasons:

- Money stays `BigDecimal` end to end. Classic Vault's API is `double`-only, so the Vault
  provider has to refuse any payout above 2⁵³ rather than credit a different number than
  the one it quoted.
- Named currencies work, so `economy.currency: Sheckles` lets the garden pay in its own
  currency while the rest of the server trades in the main one. Classic Vault has no such
  concept, and says so in the console rather than ignoring the setting silently.

`/gs info` reports which one was actually picked.

---

## Build

Only a JDK 21 is needed — the Gradle wrapper brings its own Gradle (9.6.1).

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

Then, from the project root:

```powershell
.\gradlew pluginJar                   # builds, prints the file to install and its SHA-256
.\gradlew packZip                     # rebuilds the resource pack and prints its SHA-1
.\gradlew build                       # compiles both modules and runs all 260 tests
.\gradlew test -PshowTestOutput       # also prints the full 180-row calibration sheet
```

One of the 260 skips on a fresh clone, and says why when it does: it checks the composed type names
against the names actually declared in the pack, so it needs `scripts/gen-expected-types.ps1` run
against your copy of the pack first. Everything else runs without one.

### Installing

Take `LKGardenShop-1.0.1.jar` from
[Releases](https://github.com/EliptikWu/LK-GardenShop/releases) and drop it in `plugins/`, or build
your own with `.\gradlew pluginJar`, which prints the path so you do not have to remember it. Either
way that is the **only** jar to copy; `gardenshop-core` and ItemBridge are bundled inside it and must
not be installed separately.

**Checking what you downloaded.** Every release carries a `.sha256` next to the jar, and the build is
reproducible: timestamps are zeroed and entry order is fixed, so the same commit built with the same
JDK 21 yields a byte-identical jar. A clone of this tag therefore reproduces the published hash —
which is the point of publishing it.

```powershell
Get-FileHash .\LKGardenShop-1.0.1.jar -Algorithm SHA256
```

**Then give MythicMobs some crops to sell.** The pack is bought separately, so a fresh install has
180 configured harvest types and MythicMobs knows none of them. The plugin starts anyway and says so
in one line, rather than leaving you to work it out from an empty shop:

```
Crop pack   NOT INSTALLED — the crops are a separate purchase, From Seed to Sky. Put its
            items in plugins/MythicMobs/Items/ and restart, or point crops.yml at the
            tokens of the pack you do have. Selling and the shop menu are refused until
            you do. Get it at https://mcmodels.net/products/16939/...
```

Buy [From Seed to Sky](https://mcmodels.net/products/16939/from-seed-to-sky-ultimate-farming-expansion),
put its items file in `plugins/MythicMobs/Items/`, restart, and that row turns green — the shipped
`crops.yml` already names its six crops. Using a different pack instead: point `crops.yml` at its
tokens. Either way the three config files are written on first run.

Compiled against Paper **1.21.3**, so it runs on 1.21.3 and every later 1.21.x.

### Version pinning worth knowing about

The plugin compiles against **paper-api 1.21.3** so the jar runs on any 1.21.3+ server.
The tests compile against **1.21.11** instead, because MockBukkit refuses to start unless
the Paper API on its classpath is the exact version it was built against. Both are pinned
separately in [`gradle/libs.versions.toml`](gradle/libs.versions.toml); bumping MockBukkit
means bumping `paper-test` to whatever version its error message names.

The root project deliberately has no `java` plugin. With it applied, Gradle produced an
empty `build/libs/LK-GardenShop-<version>.jar` — one hyphen away from the real plugin jar — and
installing that decoy fails with *"does not contain a paper-plugin.yml or plugin.yml"*.
No java plugin means no jar task means the trap cannot come back.

MythicCrucible is not a build dependency. Its published POM references an unresolved
`${mythiccrucible.version}` property that breaks Gradle resolution, and no Crucible class
is actually referenced — it still has to be installed on the server, since it provides the
furniture the crops are made of.

### Project layout

```
gardenshop-core/     pricing engine — pure Java, zero Bukkit imports, unit-tested
gardenshop-bukkit/   the plugin — adapters for Mythic, Vault, PlaceholderAPI, commands
scripts/             regenerate weights.yml and the test fixture from the Mythic pack
```

The split is enforced by a test: `PackageBoundaryTest` fails if anything in `core` ever
mentions `org.bukkit`. That is what keeps the 180-type pricing matrix testable in
milliseconds instead of needing a server.

---

## Configuration

Five files, all reloadable with `/gs reload` except where noted.

Only three files are written to `plugins/LKGardenShop/`:

| File | |
|---|---|
| `config.yml` | Language, economy, resource pack, item tagging, sell limits. The `stats` and `console` blocks need a restart. |
| `crops.yml` | The six crops: base value, reference weight, Mythic token. |
| `pricing.yml` | **The file you tune.** Weight bands, variant and mutation multipliers. |

Three more live **inside the jar** and are never written out, because they are not things a
server owner should be editing:

| | Why it is internal |
|---|---|
| `gui.yml` | Slot positions are tied to the backdrop texture. Moving a button without redrawing the art just puts it somewhere the art does not expect. |
| `weights.yml` | 180 weight ranges generated from the Mythic pack by `scripts/gen-weights.ps1`. |
| `lang/messages_es.yml`, `lang/messages_en.yml` | Text, not configuration. Picked with `language:`. |

**None of them is locked.** Drop a file of the same name into the plugin folder (`lang/` for a
translation) and it wins over the bundled one. A broken override falls back to the bundled copy
with a warning rather than taking anything down — there is a test for that.

One more file appears only if you use it. `adapter-bindings.yml` is written by
`/gs adapter bind` and is the only file this plugin ever writes to itself — see
[Mapping an item from another plugin](#mapping-an-item-from-another-plugin).

**English (`language: en`) is the default; `es` is the other bundled language.** Both are complete,
and a test fails if either gains a key the other lacks.

The default matches the fallback on purpose: a key missing from a translation falls back to English
rather than showing players `gui.sell-menu.held.name`, so a server that never touches the setting
reads in one language instead of finding English holes in Spanish text.

A reload is all-or-nothing. If any file has an error, **nothing** is applied: the previous
settings stay live and the errors are listed back to you with file and path. You cannot
half-break the shop.

### How a price is reached

Default mode, `HYBRID`:

```
price = base-value  x  weight-band  x  variant  x  mutations  x  global
```

The weight band is the part you tune per range. `pricing.yml` ships a six-rung ladder in
`RATIO` mode, meaning bounds are `weight / base-weight` — one ladder fits all six crops
because each tops out near 8× its own reference weight, so Ice Cotton at 0.80 kg and
Mandragora at 5.50 kg land in the same top band.

`interpolate: true` blends between rungs so the curve is continuous and never decreasing.
Without it, a crop 0.01 kg over a boundary is worth a full step more and everything in the
middle of a band is worth the same — players find those edges.

Two other modes are a config line away:

- `BANDS` — a flat amount per weight bracket. The most literal reading of "this weight
  pays this much".
- `FORMULA` — `base x (weight / base-weight)^2 x mutations`, which is Grow a Garden's own
  curve. Twice the weight is four times the price.

### What the shipped defaults actually produce

`/gs prices` in game, or `.\gradlew test -PshowTestOutput` offline:

| Crop | Typical | Cheapest | Dearest | Multiple |
|---|---:|---:|---:|---:|
| odre | 13.20 | 10.80 | 12,744 | 1180× |
| chilli | 8.80 | 7.20 | 8,496 | 1180× |
| blue_beet | 16.13 | 13.20 | 15,576 | 1180× |
| ice_cotton | 6.60 | 5.40 | 6,372 | 1180× |
| mandragora | 47.67 | 39.00 | 51,780 | 1328× |
| carrot_cross | 11.00 | 9.00 | 10,620 | 1180× |

*Typical* is a plain unmutated drop at average weight — the everyday case. *Dearest* is a
Rainbow End drop at maximum weight, the rarest thing the pack can produce.

Five of the six crops land on exactly the same 1180× spread, which is not a coincidence:
it falls out of the pack scaling every crop's weight range to ~8× its own base, so one
shared `RATIO` ladder treats them identically. Mandragora reaches 1328× only because its
top weight is 8.46× its base rather than 8.0×. If you edit multipliers and one crop's
multiple drifts far from the others, that is the signal something is off.

`PriceSweepTest` holds those relationships to loose bounds — Rainbow must beat Gold must
beat Normal for every crop, and no crop's everyday value may drift more than 15× from the
weakest — so a fat-fingered multiplier fails the build instead of quietly wrecking the
economy.

---

## Placeholders

With PlaceholderAPI installed, `%gardenshop_<key>%`:

```
hand_value  hand_unit  hand_weight  hand_band  hand_species  hand_variant  hand_mutations
inventory_value  inventory_items  inventory_stacks
total_earned  items_sold  sales_count  record_<crop>
economy_provider  currency  pricing_mode  types  crops
```

Add `_raw` to the money keys for an unformatted number. Anything reading the held item
returns an empty string for an offline player rather than a misleading zero.

---

## A note on the pack's weight lore

The pack writes each harvest's weight as a lore line:

```yaml
- "§r&f&lWeight: &r1.<random.05to40>kg"
```

MythicMobs does not zero-pad `<random.AAtoBB>`. A roll of `7` therefore renders as
**`1.7kg`** where `1.07kg` was clearly meant, and roughly forty ranges in the pack have a
lower bound starting with a zero. After the fact the two are indistinguishable.

So the plugin owns the weight instead of reading it. It rolls from `weights.yml`, stores
the result in the item's `PersistentDataContainer`, and rewrites the lore line to match at
two decimals. That also means a renamed item cannot claim to weigh 999 kg — there is a
test for exactly that.

Items harvested before the plugin was installed have their weight recovered from the lore
and clamped into the range their type is supposed to roll within, so they end up slightly
over-valued at worst.

`weights.yml` was generated from the pack, interpreting each `AAtoBB` pair as the two
decimals it was written as. Regenerate it after adding crops:

```powershell
.\scripts\gen-weights.ps1
.\scripts\gen-expected-types.ps1   # keeps the composer test honest
```

**The pack itself is left alone on purpose.** Fixing the cause would mean either rewriting
~40 ranges so no lower bound starts with a zero, or using a decimal-aware random
placeholder — and MythicMobs' published documentation does not confirm one exists. Guessing
at the syntax risks breaking a pack that currently works, to fix a symptom the plugin
already handles. If you find that `<random.>` does take decimals in your Mythic version,
that is the cleaner fix and `weights.yml` can then be regenerated to match.

---

## Adding a crop

1. Add the plant and its 30 drop items to your pack's Mythic items file — `growGardenItems.yml`
   in From Seed to Sky.
2. Add one entry to `crops.yml` — token, display name, base value, reference weight, and
   the plain drop's weight range.
3. Run `scripts/gen-weights.ps1`.
4. `/gs reload`, then `/gs info`.

The plugin derives all 30 type names from that single entry: it never reads them
individually. Note `mythic-token` is the pack's fragment, which is not always the crop
name — Odre's is `NC`.

`/gs info` lists any type the config produces that the pack does not actually have, which
is the fastest way to catch a typo.
