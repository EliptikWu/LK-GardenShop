==============================================================================
  LKGardenShop 1.0.1
  Sell Grow a Garden style harvests, priced by weight, paid through Vault
==============================================================================

  The basics: what to install, and the commands.
  Support / soporte:  https://discord.com/invite/ZfCC7amBu7

  English first. Español más abajo.


==============================================================================
  ENGLISH
==============================================================================

WHAT YOU NEED
-----------------------------------------------------------------------------

  Paper 1.21.3 or newer, Java 21

  MythicMobs 5 + MythicCrucible
      They run the crops themselves.

  The crop pack: "From Seed to Sky: Ultimate Farming Expansion"
      Sold separately, on mcmodels.net. This plugin is the shop, not the
      produce - WITHOUT THE PACK THERE IS NOTHING TO SELL. The shipped
      crops.yml is already written for its six crops.

  Vault or VaultUnlocked, plus any economy plugin
      EssentialsX, CMI, CoinsEngine, ExcellentEconomy... this is what pays
      the player. Either Vault works; VaultUnlocked is preferred if both are
      installed.

  PlaceholderAPI - optional.


INSTALL
-----------------------------------------------------------------------------

  1. Put LKGardenShop-1.0.1.jar in your plugins/ folder.
     It is the only jar to copy. Everything it needs is inside it.

  2. Put the crop pack's items file in plugins/MythicMobs/Items/.

  3. Restart the server. Not /reload.

  4. Read the box the plugin prints on startup. It has one line per
     subsystem and says which of them came up. A "Crop pack: NOT INSTALLED"
     line means step 2 is missing, or crops.yml does not match your pack.

  5. Type /gs in game.


COMMANDS
-----------------------------------------------------------------------------

  /gs                     open the shop
  /gs value               what is the crop in my hand worth (sells nothing)
  /gs sell hand           sell the stack I am holding
  /gs sell all            sell every crop in my bag, skipping favorites
  /gs favorite            protect the held crop from /gs sell all
  /gs help                the commands you are allowed to run

  Admin:

  /gs prices              the whole price sheet
  /gs prices <crop>       every drop type of one crop
  /gs info                what is loaded, which economy, what is broken
  /gs reload              re-read the config files, no restart
  /gs adapter hand        every id the item you are holding answers to
  /gs adapter list [text] search the ids your item plugins know
  /gs adapter bind <crop> sell the held item as that crop
  /gs adapter unbind      undo the binding on the held item

  /gardenshop, /gs and /gshop are the same command.


PERMISSIONS
-----------------------------------------------------------------------------

  lkgardenshop.menu             everyone
  lkgardenshop.sell             everyone
  lkgardenshop.value            everyone
  lkgardenshop.favorite         everyone
  lkgardenshop.admin.reload     op
  lkgardenshop.admin.info       op
  lkgardenshop.admin.prices     op
  lkgardenshop.admin.adapter    op
  lkgardenshop.admin            op - includes the four above

  Worth knowing: the price book is op by default, and that covers both
  /gs prices and the book button inside the shop menu. If you want players
  to browse prices, grant lkgardenshop.admin.prices to everyone.


THE SHOP ARTWORK
-----------------------------------------------------------------------------

  Nothing to set up. The menu's artwork travels inside the jar and is sent
  to players when they join - no URL to host, no hash to paste.

  It is cosmetic. A player who declines the download gets a plain chest
  menu instead; nothing breaks and nothing is unsellable.


FILES YOU CAN EDIT
-----------------------------------------------------------------------------

  plugins/LKGardenShop/

    config.yml     economy, language, resource pack, sell limits
    crops.yml      each crop's base value, and its name inside MythicMobs
    pricing.yml    weight bands and multipliers - the file you tune

  Language: config.yml -> language: en (default) or es.

  Run /gs reload after any change. A reload is all or nothing: if a file
  has an error, the previous settings stay live and the errors are listed
  back to you, so the shop cannot end up half broken.


IF NOTHING IS SELLABLE
-----------------------------------------------------------------------------

  Run /gs info, or read the startup box again. Nearly always the crop pack:
  either it is not in plugins/MythicMobs/Items/, or crops.yml names crops
  your pack does not have. While that is the case the plugin refuses to
  sell on purpose, so players are told the server is missing its pack
  instead of being told they have nothing to sell.


LICENCE
-----------------------------------------------------------------------------

  Free to run on any server you operate, including one that charges its
  players. Please do not re-upload it, resell it, or bundle it with
  anything sold or given away.


==============================================================================
  ESPAÑOL
==============================================================================

QUÉ NECESITAS
-----------------------------------------------------------------------------

  Paper 1.21.3 o superior, Java 21

  MythicMobs 5 + MythicCrucible
      Son los que crean los cultivos.

  El pack de cultivos: "From Seed to Sky: Ultimate Farming Expansion"
      Se compra aparte, en mcmodels.net. Este plugin es la tienda, no la
      cosecha: SIN EL PACK NO HAY NADA QUE VENDER. El crops.yml que viene
      incluido ya está escrito para sus seis cultivos.

  Vault o VaultUnlocked, más cualquier plugin de economía
      EssentialsX, CMI, CoinsEngine, ExcellentEconomy... es lo que le paga
      al jugador. Sirve cualquiera de los dos Vault; si tienes los dos se
      usa VaultUnlocked.

  PlaceholderAPI, opcional.


INSTALACIÓN
-----------------------------------------------------------------------------

  1. Pon LKGardenShop-1.0.1.jar en tu carpeta plugins/.
     Es el único jar que hay que copiar. Todo lo que necesita va dentro.

  2. Pon el archivo de items del pack de cultivos en
     plugins/MythicMobs/Items/.

  3. Reinicia el servidor. No uses /reload.

  4. Lee el cuadro que el plugin imprime al arrancar. Trae una línea por
     cada cosa y te dice cuál quedó funcionando. Una línea
     "Crop pack: NOT INSTALLED" significa que falta el paso 2, o que
     crops.yml no coincide con tu pack.

  5. Escribe /gs en el juego.


COMANDOS
-----------------------------------------------------------------------------

  /gs                     abre la tienda
  /gs value               cuánto vale el cultivo que tengo en la mano
                          (no vende nada)
  /gs sell hand           vende el stack que tengo en la mano
  /gs sell all            vende todos los cultivos de la mochila, saltando
                          los marcados como favoritos
  /gs favorite            protege el cultivo en mano de /gs sell all
  /gs help                los comandos que puedes usar

  Admin:

  /gs prices              la lista completa de precios
  /gs prices <cultivo>    todos los tipos de drop de un cultivo
  /gs info                qué está cargado, qué economía, qué está roto
  /gs reload              recarga la configuración, sin reiniciar
  /gs adapter hand        todos los id a los que responde el item en mano
  /gs adapter list [texto]  busca entre los id de tus plugins de items
  /gs adapter bind <cultivo>  vende el item en mano como ese cultivo
  /gs adapter unbind      deshace ese enlace

  /gardenshop, /gs y /gshop son el mismo comando.


PERMISOS
-----------------------------------------------------------------------------

  lkgardenshop.menu             todos
  lkgardenshop.sell             todos
  lkgardenshop.value            todos
  lkgardenshop.favorite         todos
  lkgardenshop.admin.reload     op
  lkgardenshop.admin.info       op
  lkgardenshop.admin.prices     op
  lkgardenshop.admin.adapter    op
  lkgardenshop.admin            op, incluye los cuatro de arriba

  Ojo con uno: la lista de precios es de op por defecto, y eso vale tanto
  para /gs prices como para el botón del libro dentro del menú. Si quieres
  que los jugadores puedan ver precios, dales lkgardenshop.admin.prices.


EL ARTE DE LA TIENDA
-----------------------------------------------------------------------------

  No hay nada que configurar. El arte del menú viaja dentro del jar y se
  le envía al jugador cuando entra: sin URL que hospedar ni hash que
  pegar.

  Es cosmético. Un jugador que rechace la descarga ve un cofre normal; no
  se rompe nada y nada deja de poder venderse.


ARCHIVOS QUE PUEDES EDITAR
-----------------------------------------------------------------------------

  plugins/LKGardenShop/

    config.yml     economía, idioma, resource pack, límites de venta
    crops.yml      valor base de cada cultivo y su nombre en MythicMobs
    pricing.yml    rangos de peso y multiplicadores, el que se ajusta

  Idioma: config.yml -> language: en (por defecto) o es.

  Usa /gs reload después de cualquier cambio. La recarga es todo o nada:
  si un archivo tiene un error se queda la configuración anterior y se te
  listan los errores, así que la tienda no puede quedar a medias.


SI NO SE PUEDE VENDER NADA
-----------------------------------------------------------------------------

  Usa /gs info, o vuelve a leer el cuadro del arranque. Casi siempre es el
  pack de cultivos: o no está en plugins/MythicMobs/Items/, o crops.yml
  nombra cultivos que tu pack no tiene. Mientras eso pase el plugin se
  niega a vender a propósito, para que al jugador se le diga que al
  servidor le falta su pack, en vez de decirle que no tiene nada que
  vender.


LICENCIA
-----------------------------------------------------------------------------

  Gratis para usarlo en cualquier servidor tuyo, incluso uno que le cobre
  a sus jugadores. Por favor no lo re-subas, no lo revendas y no lo
  incluyas en nada que se venda o se regale.

==============================================================================
