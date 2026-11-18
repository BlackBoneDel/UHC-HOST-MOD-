# Roadmap (fuera del alcance de esta entrega)

UhcCore actual opera una sola partida y no incluye hooks para las extensiones siguientes:

- **Estados de staff** Online / Invisible / No Disturb, con notificaciones push de reportes. El flujo actual es revisión manual en `/uhc reports`; no existe ningún push.
- **ViaRewind**, para estudiar compatibilidad histórica 1.7.x únicamente en servidores no competitivos. Requeriría ViaVersion y ViaBackwards; mientras exista el límite de altura y el piso 1.17, no se admiten esos clientes.
- **Geyser + Floodgate / Bedrock**, con análisis independiente de hitboxes, controles e integridad PvP. No hay detección ni hook de Bedrock en el Core actual.
- **Módulo `uhccore-nms` opcional**, solo si una necesidad futura de IA de mobs no puede resolverse mediante la API pública de Paper. El Core actual no incluye paperweight-userdev ni usa NMS.

Estas notas no constituyen funciones prometidas ni disponibles por defecto.
