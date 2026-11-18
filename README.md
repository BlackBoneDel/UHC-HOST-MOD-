# UhcCore — UHC competitivo para Paper 26.3

Core GPL-3.0 para **una sola partida/mundo activo**, compilado contra Paper **26.3.build.200-stable** y **Temurin 25**. El cliente de liga debe utilizar el protocolo nativo 777. No se usa NMS, paperweight-userdev ni ProtocolLib.

## Compilar y ejecutar

```bash
./gradlew test
./gradlew pitest
./gradlew build
python3 scripts/verify_artifacts.py build/libs/uhccore-1.0.0.jar
# JAR de despliegue: build/libs/uhccore-1.0.0.jar
```

La CI ejecuta las tres fases, comprueba el JAR sombreado, las librerías reubicadas, los binarios JNI de SQLite y ambos packs ZIP, y arranca un Paper 26.3 estable oficial para verificar habilitación, comando y host HTTP; publica el JAR como artefacto de la ejecución. Colócalo en `plugins/` de Paper 26.3 (build estable ≥200). Inicia y detén el servidor para generar `plugins/UhcCore/{settings.yml,database.yml,ranks.yml,lang/}`. `database.yml` usa SQLite por defecto, sin configuración. Para MySQL, edita `database.yml` antes de reiniciar; si falla el primer contacto, se registra un `WARNING` y se conserva el servicio con SQLite. **Nunca restaures una copia MySQL suponiendo que el SQLite de emergencia contiene sus datos.**

El servidor HTTP para packs enlaza `0.0.0.0:8088` por defecto. Abre el puerto y establece `resource-pack.host-publico` en el DNS/IP accesible por los clientes. El intento de detectar una IP local sin configuración no garantiza acceso desde Internet. Un cortafuegos, NAT o proxy inverso debe enrutar el puerto; `url-externa` solo se usa explícitamente como fallback cuando el microservidor no se puede iniciar. El pack es obligatorio. Véase [compatibilidad](docs/compatibilidad.md) para packs legacy y ViaBackwards.

## Administración

| Comando | Descripción |
|---|---|
| `/uhc info` | Panel público: reglas, borde, estadísticas (3 páginas) |
| `/uhc config` | Panel admin: General, Mecánicas, Red, Mundo |
| `/uhc staff` | Cabezas reales de jugadores y herramientas de moderación |
| `/uhc start` / `/uhc reset` | Iniciar una partida / habilitar otra **tras finalizar** |
| `/uhc pregenerate start\|pause\|resume\|cancel` | Generación async persistente con ETA |
| `/uhc border <radio> <segundos>` + `confirm` | Reducir el borde manualmente (confirmación 10s) |
| `/uhc freeze <jugador>` / `/uhc unfreeze <jugador\|UUID>` | Congelar/descongelar, también desde el panel |
| `/uhc mute <jugador>` | Alternar silencio local de staff |
| `/uhc report <jugador> <motivo>` / `/uhc reports` | Cola manual de reportes |
| `/uhc top` / `/uhc lang es\|en` | Elo / idioma manual (español fallback) |
| `/uhc rank <jugador> <rango>` | Rango fallback si no hay LuckPerms |
| `/uhc seed <entero>` | Semilla al crear un mundo nuevo (no cambia un mundo ya cargado) |
| `/uhc lobby` | Mensaje `Connect` por BungeeCord en topología `PROXY` |

En staff: clic izquierdo sobre cabeza = TP (un participante vivo no puede staff-teleportarse); derecho = acciones; Shift = freeze; medio = inspección. Inventario y ender chest se copian en vistas inmutables, nunca se abren para editar. Expulsión requiere dos clics en ≤10s. Los jugadores congelados solo pueden escribir `/msg` a staff; su chat se muestra con prefijo `[Frozen]` dentro del canal correspondiente. Su salida avisa de inmediato a staff con hora UTC exacta.

En mecánicas, las fases de borde usan botones ± radio, Shift ± inicio, medio + duración y Q para eliminar. No se pueden editar reglas mientras la partida está en curso. `/uhc start` exige al menos dos participantes con perfiles ya cargados y una pre-generación completada o cancelada; no inicia con un checkpoint pendiente o pausado. La fase previa inmoviliza y protege a los participantes y bloquea a terceros en el mundo de partida. Si falla el scatter o se detiene el plugin durante la cuenta atrás, se restauran posiciones, inventarios, Ender chests, efectos y estado previo de los participantes conectados. En cuanto comienza la partida, esos inventarios anteriores quedan eliminados deliberadamente. `AUTO_DEATH` asigna la kill del combat logger al último atacante; `FORFEIT` elimina sin asignación de kill. Ambos evitan ganadores offline indefinidos. La ventana de daño (10s) y la permanencia del proxy (otros 10s tras salir) son fijas y no editables.

## Arquitectura y trazabilidad

| Secciones | Módulos y garantía |
|---|---|
| 1, 16, 17 | Gradle Kotlin DSL/Java 25; Shadow+NOTICE+GPL; JUnit/MockBukkit/PITest, CI y releases |
| 2, 10 | `ViaVersionBridge`, `ProtocolPolicy` y `PackHost`: protocolo real, clamp 755, pack obligatorio nativo/legacy |
| 3–5, 18 | Adventure, `NegativeSpace`, paneles `AdminPanel`/`InfoPanel`/`StaffPanel`, `UhcHud`, FastBoard y `TablistPackets`; un solo scheduler para 100+ jugadores |
| 6, 7 | `UhcGameManager`, `CombatLoggerManager`, `UhcFreezeManager`, `TimeBombManager`, `GoldenHeadManager`, `RandomKillerManager`, `ScatterManager`, `SpectatorManager` |
| 8, 15 | `StatsManager` (Elo, asistencia y leaderboard), `ReportManager`, `DiscordAnnouncer`, HikariCP/SQLite/MySQL con cola DB limitada |
| 9, 14 | `PregenerationManager` (hasta 4 solicitudes async, checkpoint contiguo transaccional), `MatchClock` (partida única) |
| 11–13 | `RankManager`/`UhcPermissible` delegan en Bukkit/LuckPerms, `LocaleManager` es/en, `ProxyBridge` usa plugin messaging estándar |

**Límites operativos:** las lecturas de bloques, GUI y mutaciones Bukkit corren en el hilo principal. La generación usa `World#getChunkAtAsync`, los teletransportes `teleportAsync`; la base de datos y webhooks usan workers limitados. No hay una tarea repetitiva por jugador. El mundo UHC fuerza regeneración natural desactivada, PvP activado y respawn inmediato. El proxy de combate es un **zombie inmóvil, visible y dañable** con inventario retenido y ticket de chunk compartido, no un jugador falso de NMS; su hitbox es la de ese proxy, por lo que conviene probar su comportamiento en tu entorno de liga antes de desplegar. Las configuraciones de reglas se fijan al inicio de cada partida; cambios posteriores solo se admiten tras el resultado.

**Operación competitiva:** `reset` permite otra partida, pero **no regenera el terreno ni limpia cofres/bloques del mundo anterior**. Para otra partida oficial, detén Paper, crea un mundo UHC nuevo (o elimina el mundo anterior con respaldo) y vuelve a pre-generarlo antes de iniciar. Un reinicio de Paper durante una partida activa **no reanuda** esa partida en memoria: coordina reinicios únicamente entre partidas. Los datos de perfiles, reportes y checkpoints de pre-generación sí se guardan en la base de datos. Se recomienda mantener backups de mundo y base de datos sincronizados.

## Árbol del proyecto

```text
.
├── .github/
│   └── workflows/
│       ├── ci.yml
│       └── release.yml
├── docs/
│   ├── compatibilidad.md
│   └── roadmap.md
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── licenses/
│   ├── Adventure-NBT-LICENSE.txt
│   ├── FastBoard-LICENSE.txt
│   ├── HikariCP-LICENSE.txt
│   ├── Kyori-Option-LICENSE.txt
│   ├── mysql-connector-j-LICENSE.txt
│   ├── PacketEvents-LICENSE.txt
│   ├── sqlite-jdbc-LICENSE.txt
│   └── sqlite-jdbc-NOTICE.txt
├── resourcepack/
│   ├── assets/
│   │   └── uhccore/
│   │       ├── font/
│   │       │   └── negative_space.json
│   │       ├── items/
│   │       │   └── golden_head.json
│   │       ├── models/
│   │       │   └── item/
│   │       │       └── golden_head.json
│   │       └── textures/
│   │           └── item/
│   │               └── golden_head.png
│   ├── legacy/
│   │   ├── assets/
│   │   │   └── uhccore/
│   │   │       └── font/
│   │   │           └── negative_space.json
│   │   └── pack.mcmeta
│   └── pack.mcmeta
├── scripts/
│   ├── smoke_paper.py
│   └── verify_artifacts.py
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── blackbone/
│   │   │           └── uhccore/
│   │   │               ├── bridge/
│   │   │               │   └── ViaVersionBridge.java
│   │   │               ├── command/
│   │   │               │   └── UhcCommand.java
│   │   │               ├── config/
│   │   │               │   └── Settings.java
│   │   │               ├── db/
│   │   │               │   └── Database.java
│   │   │               ├── domain/
│   │   │               │   ├── BorderPhase.java
│   │   │               │   ├── BorderSchedule.java
│   │   │               │   ├── CombatWindow.java
│   │   │               │   ├── MatchAccessPolicy.java
│   │   │               │   ├── MatchClock.java
│   │   │               │   ├── PlayerStats.java
│   │   │               │   ├── ProtocolPolicy.java
│   │   │               │   ├── RatingMath.java
│   │   │               │   └── ScatterRules.java
│   │   │               ├── game/
│   │   │               │   └── UhcGameManager.java
│   │   │               ├── gui/
│   │   │               │   ├── AdminPanel.java
│   │   │               │   ├── GuiItems.java
│   │   │               │   ├── InfoPanel.java
│   │   │               │   ├── MenuProtection.java
│   │   │               │   ├── StaffPanel.java
│   │   │               │   └── UhcMenu.java
│   │   │               ├── hud/
│   │   │               │   ├── TablistPackets.java
│   │   │               │   └── UhcHud.java
│   │   │               ├── i18n/
│   │   │               │   └── LocaleManager.java
│   │   │               ├── libs/
│   │   │               │   └── sqlite/
│   │   │               │       └── SQLiteDriver.java
│   │   │               ├── mechanic/
│   │   │               │   ├── CombatLoggerManager.java
│   │   │               │   ├── GoldenHeadManager.java
│   │   │               │   ├── MatchProtection.java
│   │   │               │   ├── MuteManager.java
│   │   │               │   ├── RandomKillerManager.java
│   │   │               │   ├── SpectatorManager.java
│   │   │               │   ├── TimeBombManager.java
│   │   │               │   └── UhcFreezeManager.java
│   │   │               ├── network/
│   │   │               │   ├── DiscordAnnouncer.java
│   │   │               │   └── ProxyBridge.java
│   │   │               ├── permission/
│   │   │               │   ├── RankManager.java
│   │   │               │   └── UhcPermissible.java
│   │   │               ├── resource/
│   │   │               │   ├── NegativeSpace.java
│   │   │               │   └── PackHost.java
│   │   │               ├── stats/
│   │   │               │   ├── ReportManager.java
│   │   │               │   └── StatsManager.java
│   │   │               ├── world/
│   │   │               │   ├── PregenerationManager.java
│   │   │               │   ├── ScatterManager.java
│   │   │               │   └── WorldManager.java
│   │   │               └── UhcCorePlugin.java
│   │   └── resources/
│   │       ├── lang/
│   │       │   ├── en.yml
│   │       │   └── es.yml
│   │       ├── database.yml
│   │       ├── paper-plugin.yml
│   │       ├── ranks.yml
│   │       └── settings.yml
│   └── test/
│       └── java/
│           └── com/
│               └── blackbone/
│                   └── uhccore/
│                       ├── config/
│                       │   ├── SettingsTest.java
│                       │   └── TranslationsTest.java
│                       ├── domain/
│                       │   ├── BorderScheduleTest.java
│                       │   ├── CombatWindowTest.java
│                       │   ├── MatchAccessPolicyTest.java
│                       │   ├── MatchClockTest.java
│                       │   ├── PlayerStatsTest.java
│                       │   ├── ProtocolPolicyTest.java
│                       │   ├── RatingMathTest.java
│                       │   └── ScatterRulesTest.java
│                       ├── mechanic/
│                       │   ├── PersistenceTest.java
│                       │   ├── RandomKillerManagerTest.java
│                       │   ├── RankManagerTest.java
│                       │   └── UhcFreezeManagerTest.java
│                       └── world/
│                           ├── PregenerationCheckpointTest.java
│                           └── WorldManagerTest.java
├── .gitignore
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE
├── NOTICE
├── README.md
└── settings.gradle.kts
```

[Roadmap](docs/roadmap.md) · [Licencia](LICENSE) · [Avisos de terceros](NOTICE)
