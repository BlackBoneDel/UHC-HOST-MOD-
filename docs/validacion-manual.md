# Validación de liga antes del despliegue

La CI arranca Paper 26.3 en Java 25 y verifica el plugin, el comando, los packs HTTP y un checkpoint SQLite de pre-generación. **No conecta clientes reales, no reanuda una generación tras reiniciar Paper y no disputa una partida completa.** Este protocolo de pruebas usa un servidor de staging y no sustituye una auditoría de PvP de la liga.

## Evidencia pendiente (2026-11-18 UTC)

| Prueba exigida | Situación verificable | Evidencia que debe anexar el operador antes de liga |
|---|---|---|
| Partida completa con ≥2 clientes reales 26.3 | **NO EJECUTADA** en este entorno (sin dos cuentas ni clientes gráficos) | Vídeo/log con UUID anonimizados y hora, pasos 1–7 y resultado/ranking confirmados en DB. |
| 1.17 + 26.3 con ViaVersion/ViaBackwards y pack obligatorio | **NO EJECUTADA** con clientes reales ni plugins externos aquí | Logs de protocolo observado, SHA-1/HTTP 200 del pack nativo y legacy, rechazo de pack y de clientes fuera del rango. |
| Reanudar pregeneración tras reiniciar Paper | **NO EJECUTADA** en Paper; la prueba MockBukkit de reapertura de DB/gestor no simula un reinicio del servidor | Log antes/después con `generation.next_index`, `generated`, `state`, avance posterior al `resume` sin saltos ni duplicación perjudicial. |
| Carga de ≥100 conexiones simultáneas | **NO EJECUTADA**; la CI y el sandbox no son hardware de liga ni generan clientes | TPS/MSPT de Paper, percentiles de latencia HUD/tablist y HTTP del pack, concurrencia y tiempo de descarga en hardware equivalente. |
| MySQL, Discord y proxy reales | **NO EJECUTADOS**; no hay esos servicios ni credenciales de staging | Logs de conexión/fallback, webhook y transferencia, sin exponer secretos. |

Ninguna prueba unitaria o arranque de CI debe registrarse como evidencia sustitutiva de estos recorridos. Un responsable de liga debe fechar y firmar un acta por cada fila antes de habilitar partidas oficiales; si falla una, no publicar una release **de liga** ni dar por validada la capacidad.

## Preparación reproducible

1. En un **host real de staging**, descargar el JAR `uhccore-1.0.1-rc.1.jar` de la [prerelease v1.0.1-rc.1](https://github.com/BlackBoneDel/UHC-HOST-MOD-/releases/tag/v1.0.1-rc.1), anotar su SHA-256 (`sha256sum uhccore-1.0.1-rc.1.jar`) y colocarlo como único UhcCore en `plugins/` de Paper **26.3** estable en Java **25**, con `online-mode=true` para la partida humana. No usar ProtocolLib ni un JAR de fuentes; no confundir esta prerelease con autorización de liga.
2. Antes del primer arranque, configurar un DNS/IP accesible por los clientes en `resource-pack.host-publico` (tras generar `plugins/UhcCore/settings.yml`) y abrir el puerto TCP `resource-pack.puerto` (8088 por defecto). No usar una IP privada detrás de NAT sin reenvío. Reiniciar Paper para aplicar el cambio.
3. Usar **dos clientes oficiales 26.3** con cuentas distintas y un operador en consola para los comandos administrativos. Si entra una cuenta de staff adicional, quitarle `uhccore.play` para que no sea participante accidental. Para un ensayo rápido en un mundo **nuevo**, configurar `mundo.radio: 256` antes de crear el mundo; mantener una distancia de scatter válida para ese radio.
4. Guardar copia de `plugins/UhcCore/` y del mundo UHC antes de ejecutar pruebas destructivas. No reutilizar ese mundo para una partida oficial posterior.

## Recorrido de una partida

| Paso | Acción | Resultado esperado |
|---|---|---|
| 1 | Entrar con ambos clientes; revisar `/uhc info` y las tres páginas, `/uhc config` con una cuenta admin y `/uhc staff` con una cuenta staff. | Se abren paneles paginados, textos es/en y HUD/pack; los clics no extraen ítems de las GUI. Los clientes sin permisos no pueden abrir paneles restringidos. |
| 2 | Ejecutar `/uhc pregenerate start`, observar el avance/ETA en la pestaña Mundo y dejar que termine; repetir en staging con `pause`, `resume` y `cancel` antes de iniciar otra generación. | Como máximo cuatro peticiones de chunks en vuelo; el estado y checkpoint sobreviven un reinicio **entre partidas**. No iniciar una partida con un checkpoint RUNNING o PAUSED. |
| 3 | Ejecutar `/uhc start` desde consola con ambos perfiles cargados. Intentar moverse, romper, colocar y dañarse durante la cuenta atrás. | Scatter en posiciones seguras separadas; inventarios y Ender chests iniciales vacíos; countdown inmóvil y sin daño; nadie ajeno puede intervenir en el mundo UHC. Si se aborta antes del inicio, se restauran los inventarios y posiciones anteriores de participantes conectados. |
| 4 | Tras la cuenta atrás, comprobar minería, daño PvP, regeneración natural desactivada, scoreboard/tablist y borde. Reducirlo con `/uhc border <radio> <segundos>` seguido de `/uhc border confirm` en menos de diez segundos. | Los vivos solo actúan en el mundo de partida; el borde encoge en el tiempo solicitado (segundos transformados a ticks). No hay curación natural por hambre. |
| 5 | Eliminar a un participante. Si está activa la timebomb, esperar su cuenta atrás y comprobar que el terreno no se rompe; recoger los ítems y observar la cabeza y sus efectos al consumirla. | Exactamente un conjunto de drops, marcador de espectador/compás, cámara a supervivientes, chat de espectadores separado, Elo y clasificación solo tras confirmar la escritura. Las cabezas ordinarias no activan los efectos de la cabeza dorada. |
| 6 | Probar en partidas de ensayo separadas: daño entre jugadores, desconexión del dañado dentro de diez segundos, muerte del proxy zombi; repetir con reconexión antes de que muera, y después sin golpear el proxy hasta que expire. | El proxy visible tiene la vida/equipo esperados; la muerte o caducidad resuelve una sola eliminación y un solo botín. Reclamación antes de su muerte devuelve al jugador al combate. Comparar hitbox y timing con los clientes reales de liga. |
| 7 | Finalizar la partida, consultar `/uhc top` y los reportes; reiniciar Paper **después** del resultado. | Perfiles, puntuaciones, reportes y generación siguen en SQLite/MySQL. `reset` solo se permite tras resultado confirmado y limpia etiquetas de combate; **no** regenera el mundo ni reanuda una partida interrumpida. |

**Acta de partida humana (ejecutar pasos 1–7, no solo comprobar el arranque):** con las dos cuentas reales 26.3 elegibles y `online-mode=true`, configurar un webhook **privado de staging** en `discord.webhook-url` antes de arrancar Paper; si no hay webhook, marcar explícitamente la prueba Discord como no realizada. Anotar hora/UUID anonimizados, coordenadas y separación del scatter, daño PvP, hitbox del proxy, radio/tiempo del borde, botín único tras la muerte, entrada a espectador (compás/chat), ganador y mensajes `UHC started`, `UHC elimination`, `UHC finished` recibidos en Discord. Esperar a que se confirme el resultado antes de `/uhc reset`; comparar `/uhc top` con `SELECT uuid,kills,deaths,wins,played,elo FROM players` y `SELECT id,winner_uuid,ranking FROM matches ORDER BY ended_ms DESC LIMIT 1` en la **DB activa**. Adjuntar log del servidor, evidencia de los dos clientes, recepción del webhook y filas de DB (sin revelar secretos/UUID completos). Si cualquiera falta, no dar la partida por validada.

Antes de finalizar, hacer entrar **tarde** a una tercera cuenta con ítems distintos en inventario, armadura, mano secundaria y Ender chest. Confirmar que `plugins/UhcCore/late-join-inventories/<UUID>.dat` aparece **antes** de equiparla de espectador, que no conserva duplicados durante la partida, y que recupera exactamente los cuatro contenidos al acabar o al reconectar fuera de partida, tras un reinicio. Si falla `saveData()`/la restauración, el archivo debe permanecer y el jugador no debe seguir jugando; respaldar DB/mundo/archivo y reparar manualmente sin iniciar otra partida.

## Reanudar pregeneración tras apagado forzado (staging; pendiente de ejecutar)

1. Usar un mundo y SQLite **desechables**, con un radio lo bastante grande para no completar la generación antes del apagado (el valor inicial de `mundo.radio` es 1200). Con Paper y el JAR de la prerelease, ejecutar `/uhc pregenerate start`. Esperar progreso **RUNNING** y `generated > 0` con checkpoint confirmado; guardar hora/log y consultar la fila de SQLite desde la carpeta raíz de Paper (requiere la CLI `sqlite3`):

   ```sh
   sqlite3 'plugins/UhcCore/data/uhc.db' 'SELECT world_name,state,next_index,generated,started_ms FROM generation WHERE id=1;'
   ```

2. Como control separado, ensayar `pause` → **PAUSED** sin `PENDING/FAILED` → apagado limpio (`stop`) → reinicio con **el mismo** JAR, mundo y DB → `/uhc pregenerate resume`; comprobar que el contador vuelve a aumentar. Para la prueba exigida de caída a mitad de proceso, dejar el estado **RUNNING**, anotar otra fila confirmada y forzar la terminación **solo del proceso Java de este staging** (verificar el PID/argumentos antes de ejecutar `kill`; no hacer esto en producción):

   ```bash
   read -r -p 'PID de Paper staging: ' PAPER_PID
   ps -p "$PAPER_PID" -o pid,args=
   # Tras confirmar manualmente que es esta instancia de Paper:
   kill -KILL "$PAPER_PID"
   ```

3. Sin borrar ni copiar parcialmente `uhc.db`/`uhc.db-wal`/`uhc.db-shm` ni el mundo, reiniciar Paper con la misma configuración. Buscar en consola/log `Restored world generation: RUNNING (X/Y)` y repetir la consulta SQLite: `world_name` y `started_ms` deben ser los mismos, los contadores no deben retroceder respecto al **último checkpoint confirmado** (puede haberse escrito otro entre la consulta y el kill) y `generated` debe seguir creciendo posteriormente hasta **COMPLETED**. Si vuelve en **RUNNING**, reanuda automáticamente: **no** enviar `/uhc pregenerate resume` (solo se usa si vuelve **PAUSED**). Conservar logs y filas antes/después, verificar ausencia de saltos o pérdida de chunks; si completó antes del kill, repetir con un radio mayor. Un fallo de escritura debe mostrar **FAILED**, parar el trabajo y no anunciar **COMPLETED**. MockBukkit no sustituye este reinicio real de Paper.
4. En otro ensayo, forzar fallo DB durante una eliminación: no debe anunciarse resultado/ganador ni permitirse `/uhc reset` hasta reparar la integridad. Conservar DB/mundo para reparación manual.

## Carga de 100+ conexiones con mc-bots (staging aislado; pendiente de ejecutar)

1. Usar [crpmax/mc-bots](https://github.com/crpmax/mc-bots) (MIT, Java **17+**), preferentemente el JAR publicado `mc-bots-1.2.17.jar`, descargado de sus [releases](https://github.com/crpmax/mc-bots/releases); registrar versión y hash. El README del proyecto (1.2.17) indica bots **1.21.7** (servidores hasta 1.21.8), **no confirma soporte nativo de Paper 26.3**. Antes de contar carga, probar **un bot** y observar protocolo aceptado, permanencia en `/list` y respuesta al pack obligatorio. Si no negocia 26.3 directamente, instalar versiones compatibles de ViaVersion **y** ViaBackwards y activar `compatibilidad.modo: FLEXIBLE` (piso ≤ protocolo del bot, nunca <755) **solo en este staging**. Si el bot no completa login/pack o es expulsado, registrar el motivo y **no** contarlo: comprobar si existe una versión compatible del bot o elegir otro generador que soporte el pack; no atribuirle una prueba de 100 conexiones al mero intento de login. No asumir que mc-bots descarga el ZIP: comprobarlo por separado.
2. Para nombres de bots no autenticados, usar **otra instancia privada y desechable** (`online-mode=false`, `max-players` ≥110, firewall que solo permita el generador y los observadores); **nunca** desactivar autenticación en el servidor público ni compartir la DB/mundo de liga. Con un bot admitido y persistente, desde un host generador distinto ejecutar (sustituir `STAGING_HOST`; `-d` es el retardo mínimo/máximo entre conexiones en ms):

   ```sh
   STAGING_HOST='staging.ejemplo.net'
   java -jar mc-bots-1.2.17.jar -s "${STAGING_HOST}:25565" -p BOT_ -d 2000 4000 -c 100 2>&1 | tee bots-load.log
   ```

   Verificar **≥100 bots simultáneamente conectados**, no solo 100 intentos, con `.list` en mc-bots y `/list` en Paper; registrar desconexiones y fase de rampa (aprox. 2–4 segundos entre joins). Mantener al menos cinco minutos de carga estable y un cliente humano 26.3 de referencia para observar HUD. Al terminar, restaurar `online-mode=true` y `ESTRICTO` **antes** de reutilizar staging para pruebas humanas.
3. Durante línea base, rampa, estado estable, descargas y recuperación, guardar hora, `/tps`, `/mspt` cada 30 s, p50/p95/p99 de MSPT y cambios de Tablist/Scoreboard medidos en el cliente de referencia (captura de paquetes/tiempos de pantalla; **no** inferirlos del terminal de mc-bots). Para cargar por separado el microservidor HTTP mientras entran bots, desde una máquina autorizada ejecutar 100 GET con 20 descargas simultáneas; repetir con `/uhccore-legacy-pack.zip` si se usa FLEXIBLE. El host del pack debe ser alcanzable desde **esa** red y los códigos deben ser 200:

   ```bash
   STAGING_HOST='staging.ejemplo.net' # sustituir también en la máquina de descargas
   PACK_URL="http://${STAGING_HOST}:8088/uhccore-pack.zip"
   set -o pipefail
   seq 100 | xargs -P 20 -I{} sh -c 'curl -sS -o /dev/null -w "%{http_code},%{time_total},%{size_download}\n" "$1"' _ "$PACK_URL" | tee pack-http.csv
   ```

   Registrar p50/p95/p99 del tiempo HTTP, errores, tamaños, SHA-1/ETag, concurrencia y colas; contrastar los ZIP contra los servidos por el plugin. Si mc-bots no solicita el pack por sí mismo, **esta carga HTTP sintética es distinta de descargas de clientes reales**: anotarlo sin ocultarlo. Adjuntar CSV, logs, versión de Paper/Via/bot, CPU/RAM/red y criterio de aceptación de liga; no inventar resultados.

**Interpretación:** los bots son una **aproximación de red/CPU**, no reproducen el combate, hitboxes, renderizado, latencia percibida ni aceptación real de packs. **No sustituyen** la partida completa con clientes humanos del apartado anterior, ni avalan el modo FLEXIBLE para liga.

## Moderación, red y carga

- Congelar a un cliente con `/uhc freeze <jugador>`: movimiento, inventario, ataques y comandos ajenos a `/msg` hacia staff quedan bloqueados; por decisión **ruta A (§6.7)** el chat normal `[Frozen]` llega a **todos** los conectados (incluidos staff y espectadores). Verificarlo con tres cuentas y valorar el riesgo de divulgar tácticas. Su desconexión notifica a staff con hora UTC. Probar `/uhc unfreeze`, mute, reportes y confirmación de expulsión en GUI.
- En **otro servidor de staging**, instalar ViaVersion y ViaBackwards, activar FLEXIBLE y probar un cliente real 1.17 (protocolo 755) y el pack legacy; probar rechazo de versiones inferiores y superiores a la admitida. Mientras se comprueban los cuatro ticks del join, intentar moverse, atacar, colocar bloques y ejecutar comandos: todo debe quedar bloqueado hasta la admisión. Sin ViaVersion, FLEXIBLE no se activa. Para la liga, volver a **ESTRICTO**, solo protocolo nativo 777.
- Probar el webhook Discord, `PROXY`/BungeeCord y MySQL con servicios reales en staging. Si MySQL falla en el primer contacto, confirmar el `WARNING` y el fallback SQLite **sin asumir que contiene los datos de MySQL**. No guardar URLs de webhook ni contraseñas en tickets públicos.
- Ejecutar el ensayo de 100+ bots y descargas HTTP descrito arriba en hardware comparable a producción; añadir cola DB y progreso de pregeneración a la hoja de métricas. La CI no certifica esa capacidad ni la equivalencia competitiva de hitboxes con versiones traducidas.

**Criterio de publicación:** el tag `v1.0.1-rc.1` es una **prerelease candidata** para descargar y validar el JAR versionado; publicar el artefacto no autoriza su uso en liga. No etiquetar una release **estable/de liga** hasta completar el recorrido con clientes reales, verificar integridad del botín y probar los componentes opcionales que se vayan a habilitar. Para la siguiente partida oficial, usar un mundo UHC nuevo o restaurar uno virgen junto con el backup de base de datos correspondiente.
