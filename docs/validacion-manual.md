# Validación de liga antes del despliegue

La CI arranca Paper 26.3 en Java 25 y verifica el plugin, el comando, los packs HTTP y el checkpoint SQLite de pre-generación. **No conecta clientes reales ni disputa una partida completa.** Este protocolo de pruebas usa un servidor de staging y no sustituye una auditoría de PvP de la liga.

## Preparación reproducible

1. Crear una instalación limpia de Paper **26.3** estable en Java **25**, con `online-mode=true`; instalar el JAR sombreado que publicó la CI como único `UhcCore` en `plugins/`. No usar ProtocolLib ni un JAR de fuentes.
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
| 5 | Eliminar a un participante. Si está activa la timebomb, esperar su cuenta atrás y comprobar que el terreno no se rompe; recoger los ítems y observar la cabeza y sus efectos al consumirla. | Exactamente un conjunto de drops, marcador de espectador/compás, cámara a supervivientes, chat de espectadores separado, Elo y clasificación actualizados. Las cabezas ordinarias no activan los efectos de la cabeza dorada. |
| 6 | Probar en partidas de ensayo separadas: daño entre jugadores, desconexión del dañado dentro de diez segundos, muerte del proxy zombi; repetir con reconexión antes de que muera, y después sin golpear el proxy hasta que expire. | El proxy visible tiene la vida/equipo esperados; la muerte o caducidad resuelve una sola eliminación y un solo botín. Reclamación antes de su muerte devuelve al jugador al combate. Comparar hitbox y timing con los clientes reales de liga. |
| 7 | Finalizar la partida, consultar `/uhc top` y los reportes; reiniciar Paper **después** del resultado. | Perfiles, puntuaciones, reportes y generación siguen en SQLite/MySQL. `reset` limpia etiquetas de combate, pero **no** regenera el mundo ni reanuda una partida interrumpida. |

## Moderación, red y carga

- Congelar a un cliente con `/uhc freeze <jugador>`: movimiento, inventario, ataques y comandos ajenos a `/msg` hacia staff quedan bloqueados; el chat normal del congelado solo llega al staff y al emisor con `[Frozen]`. Su desconexión notifica a staff con hora UTC. Probar `/uhc unfreeze`, mute, reportes y confirmación de expulsión en GUI.
- En **otro servidor de staging**, instalar ViaVersion y ViaBackwards, activar FLEXIBLE y probar un cliente 1.17 (protocolo 755) y el pack legacy; probar rechazo de versiones inferiores y superiores a la admitida. Sin ViaVersion, FLEXIBLE no se activa. Para la liga, volver a **ESTRICTO**, solo protocolo nativo 777.
- Probar el webhook Discord, `PROXY`/BungeeCord y MySQL con servicios reales en staging. Si MySQL falla en el primer contacto, confirmar el `WARNING` y el fallback SQLite **sin asumir que contiene los datos de MySQL**. No guardar URLs de webhook ni contraseñas en tickets públicos.
- Ensayar carga de 100+ jugadores en hardware similar al de producción, revisar TPS, duración de ticks, cola DB, descargas del pack y avance de la pre-generación. La CI no certifica esa capacidad ni la equivalencia competitiva de hitboxes con versiones traducidas.

**Criterio de publicación:** no etiquetar una release de liga hasta completar el recorrido con clientes reales, verificar integridad del botín y probar los componentes opcionales que se vayan a habilitar. Para la siguiente partida oficial, usar un mundo UHC nuevo o restaurar uno virgen junto con el backup de base de datos correspondiente.
