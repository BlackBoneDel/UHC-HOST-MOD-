# Compatibilidad de clientes — UhcCore 26.3

## Política competitiva

El servidor **ejecuta Paper 26.3** (Java 25); el protocolo nativo se obtiene de `Bukkit.getUnsafe().getProtocolVersion()` y debe ser exactamente 777. Una futura versión de Paper con otro protocolo requiere adaptar el pack y volver a verificar el Core antes de habilitarla. ViaVersion solo traduce **paquetes**: no añade bloques, objetos ni mecánicas al servidor. La partida oficial usa `ESTRICTO`: protocolo del cliente **exactamente igual al nativo**. Esto minimiza discrepancias de hitbox, movimiento y timing. Ninguna opción de configuración de ViaVersion se modifica: UhcCore aplica sus reglas en su propio `settings.yml` y expulsa clientes no permitidos tras el cuarto tick del join.

| Modo | ViaVersion | ViaBackwards | Rango aceptado |
|---|---|---|---|
| ESTRICTO (por defecto) | opcional | opcional | nativo…nativo |
| FLEXIBLE | obligatorio | no instalado | nativo…nativo (clientes viejos imposibles) |
| FLEXIBLE | obligatorio | instalado | máximo entre 755 y piso configurado…nativo |

`755` es el protocolo de **Java 1.17** y no puede reducirse. Un piso inferior se clampa en el arranque con `WARNING`. UhcCore mantiene `compatibilidad.protocolo-minimo` y `protocolo-maximo` igual al valor nativo mientras está en modo estricto. Sin ViaVersion, la pestaña de compatibilidad deshabilita el modo flexible. En la pestaña y en `/uhc staff` aparecen la detección y la versión observada, respectivamente.

> **Aviso permanente de la GUI:** «Activar el Modo Flexible puede introducir variaciones menores de hitbox/timing de combate entre clientes de distintas versiones. No recomendado para partidas oficiales de liga.»

La identificación de protocolo usa `Via.getAPI().getPlayerVersion(player)` **4 ticks después** de `PlayerJoinEvent`. Durante esa espera UhcCore bloquea movimiento, daño y acciones del jugador sin verificar; no sustituye el rechazo de la versión fuera del rango. Protocolos desconocidos o superiores al servidor se rechazan. ViaBackwards debe estar instalado para los clientes anteriores a 26.3; no basta con instalar ViaVersion. El cliente antiguo recibe traducciones de los contenidos nativos, no el contenido original inexistente de su versión.

## Decisión de producto — Freeze, ruta A (Prompt Maestro §6.7)

Se aplica la regla original: el chat normal de un jugador congelado se cancela y se reemite con prefijo `[Frozen]` **a todos los jugadores conectados**, no solo al staff. Los comandos `/msg` dirigidos a staff siguen disponibles. **Riesgo competitivo conocido:** ese chat público puede divulgar tácticas; el staff debe supervisarlo durante una partida oficial. No se presenta como una conversación privada de moderación.

## Resource pack y conectividad

El pack nativo `resourcepack/pack.mcmeta` declara formato `[97,1]`, que corresponde a 26.3. El pack `resourcepack/legacy/` contiene la misma fuente de espacios negativos sin el modelo de ítem moderno; su metadato abarca 1.17–26.2 y se sirve a clientes antiguos en modo flexible. En esas versiones, la cabeza dorada mantiene su forma de cabeza de jugador sin el modelo custom nuevo. Los dos `.zip` se generan en Gradle y se sirven en el puerto configurado; **abrir el puerto TCP** al público y configurar `resource-pack.host-publico` con DNS/IP de acceso público (no `localhost`, no una IP privada). El servidor detecta su IP local como ayuda inicial y avisa en consola si no se configuró el host público. Se envía el pack como **obligatorio** con SHA-1; el cliente puede rechazarlo y, en ese caso, Paper impide su entrada. No hay dependencia de CDN.

Para caída del microservidor se admite `resource-pack.url-externa` y, si se habilitan clientes antiguos, `url-externa-legacy` o una URL base terminada en `/`. Las URL externas deben servir exactamente los ZIP empaquetados (mismo SHA-1). Las decisiones de protocolo se toman antes de enviar el pack.

## Procedimiento de prueba con clientes reales (pendiente en staging)

`paper-plugin.yml` solicita que **ViaVersion** y **ViaBackwards** se carguen antes de UhcCore (ambos opcionales; solo ViaVersion comparte classpath). Con Paper 26.3/Java 25 y los JAR compatibles de ambos plugins, abrir temporalmente **FLEXIBLE** con piso 755 en un servidor de ensayo, nunca en la liga sin validación. Guardar versión de cada plugin, hora UTC, logs y resultado por cliente:

1. Conectar clientes oficiales **26.3 (777)** y **1.17 (755)**; intentar mover, romper/colocar, atacar, abrir inventario y ejecutar un comando durante los cuatro ticks de verificación: no deben surtir efecto. Tras ser admitidos, comprobar el protocolo observado en `/uhc staff` y que el pack obligatorio correcto responde **HTTP 200** en `/uhccore-pack.zip` o `/uhccore-legacy-pack.zip`, con SHA-1 igual al ZIP del plugin. Verificar HUD/chat con ambos clientes, sin equiparar traducción a equivalencia competitiva.
2. Rechazar el pack en cada cliente; Paper debe expulsarlo por ser obligatorio. Probar un cliente **inferior a 755** y uno **superior a 777** solo si ViaVersion puede negociar su login: UhcCore debe expulsarlos por política con versión/rango en el mensaje. Un cliente que ni siquiera alcanza el join por incompatibilidad de red se registra como **rechazo de conexión**, no como prueba del kick del Core.
3. Quitar ViaBackwards (con ViaVersion presente) y confirmar que un 1.17 no puede entrar y FLEXIBLE queda limitado al nativo; quitar ViaVersion y confirmar que FLEXIBLE no se habilita. Restaurar **ESTRICTO** al terminar, comprobar que solo entra 777. Recoger mediciones de TPS/latencias y actas de `docs/validacion-manual.md` antes de liga.

**Limitación (2026-11-18):** el test MockBukkit de la ventana solo comprueba eventos y temporización en protocolo nativo simulado; en este entorno no hay dos clientes reales, plugins externos ni mediciones de traducción. No presentar la CI como prueba de estas integraciones.

## Extensiones no habilitadas

ViaRewind (hasta 1.7.x) es una **extensión futura**, no un hook de UhcCore. Incluso con ViaVersion y ViaBackwards, el piso duro 1.17 bloquea esas versiones porque el mundo competitivo usa alturas fuera de sus límites. No se debe cambiar la configuración de terceros para eludir este piso.
