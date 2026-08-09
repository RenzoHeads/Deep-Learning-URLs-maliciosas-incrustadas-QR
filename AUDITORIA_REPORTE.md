# AUDITORÍA QR GUARDIAN — REPORTE CONSOLIDADO

## Metodología

Tres ejes auditados en paralelo:

1. **Contrato backend↔Android** — mapeo directo DTO → endpoint → caller (lectura directa por mí)
2. **Código muerto** — agente explore `bg_bb90080a` (10m54s), verificación cruzada con grep
3. **Bugs de lógica de sync** — agente explore `bg_4c40ef50` (16m38s), verificación cruzada con DAOs y entidades

---

## EJE 1 — CONTRATO BACKEND ↔ ANDROID

Todos los DTOs Pydantic (`modelos.py`) y DTOs Kotlin (`ClienteBackend.kt`) están vivos. Los helpers `fila_a_*` también. Los hallazgos son **endpoints backend sin caller Android**:

| Endpoint | Línea backend | Caller Android | Severidad |
|---|---|---|---|
| `GET /escaneos/count` | historial.py:155 | **NINGUNO** — Android pagina en local con Room Flow | 🔴 ALTO |
| `GET /escaneos/{id}` | historial.py:219 | **NINGUNO** — Android lee por ID desde Room | 🔴 ALTO |
| `DELETE /denuncias/{id}` | denuncias.py:137 | **NINGUNO** — `RepositorioDenuncias.kt:197` confirma "no hay endpoint DELETE en v1" | 🔴 ALTO |
| `GET /estadisticas` | estadisticas.py:19 | **NINGUNO** — Android calcula stats locales vía DAO observers (`DatosTabsViewModel.kt:72,77,82`) | 🔴 ALTO |
| Rama "modo normal" `GET /escaneos` | historial.py:128-147 | Dead branch (Android siempre envía `modificados_desde`) | 🟡 MEDIO |
| Rama "modo normal" `GET /urls-bloqueadas` | bloqueadas.py:56-68 | Igual patrón | 🟡 MEDIO |
| Rama "modo normal" `GET /denuncias` | denuncias.py:119-133 | Igual patrón | 🟡 MEDIO |

Campo descartado en Android: `CategoriaDenuncia` (DTO) descarta `descripcion`, pero `CategoriaDenunciaEntity` la declara → columna local nunca se llena.

---

## EJE 2 — CÓDIGO MUERTO

### 🔴 ALTO (13 elementos — eliminar seguro, sin callers ni producción ni tests)

| # | Símbolo | Archivo:línea | Razón |
|---|---|---|---|
| 1 | `Ajustes.PORT` | config.py:15 | Config nunca leída |
| 2 | `Ajustes.ENTORNO` | config.py:18 | Config nunca leída |
| 3 | `Depends` import | auth.py:18 | Import sin uso (sólo en docstring) |
| 4 | `GET /escaneos/count` | historial.py:155-181 | Sin caller Android |
| 5 | `GET /escaneos/{id}` | historial.py:219-245 | Sin caller Android |
| 6 | `DELETE /denuncias/{id}` | denuncias.py:137-160 | Sin caller Android |
| 7 | `GET /estadisticas` | estadisticas.py:19-49 | Sin caller Android |
| 8 | `DenunciaDao.todosLosIds()` | DenunciaDao.kt:41 | Sin callers ni producción ni tests |
| 9 | `DenunciaDao.reKey()` | DenunciaDao.kt:35 | Sin callers — `procesarCreate` usa `eliminarPorId + insertar` |
| 10 | `CategoriaDao.insertarTodos()` | CategoriaDao.kt:23 | Sin callers (ni tests — el docstring miente, tests usan `upsertAll`) |
| 11 | `EscaneoDao.observarDirty()` + `RepositorioEscaneos.observarDirty()` | EscaneoDao.kt:166 / RepositorioEscaneos.kt:75 | Cadena DAO+Repo completa muerta |
| 12 | `EscaneoDao.observarReescaneos()` (paginado) + `RepositorioEscaneos.observarReescaneos()` + `observarReescaneosSnapshot()` | EscaneoDao.kt:113 / RepositorioEscaneos.kt:84,125 | ViewModels usan `observarReescaneosTodos` (no paginado) y `contarReescaneosSnapshot` |
| 13 | `RepositorioDenuncias.sincronizarDesdeBackend()` | RepositorioDenuncias.kt:97 | Legacy wrapper sin callers |

### 🟡 MEDIO (10 elementos — test-only o rama no ejercitada)

| # | Símbolo | Archivo:línea | Razón |
|---|---|---|---|
| 14 | `EscaneoDao.todosLosIds()` | EscaneoDao.kt:308 | Sólo tests (5 archivos) |
| 15 | `UrlBloqueadaDao.todosLosIds()` | UrlBloqueadaDao.kt:57 | Sólo tests (RebloqueoResurrectTest) |
| 16 | `UrlCatalogoDao.contar()` | UrlCatalogoDao.kt:45 | Sólo tests (docstring lo admite) |
| 17 | `PendingOpDao.observarPendientes()` | PendingOpDao.kt:40 | Sólo tests — SyncWorker usa `minPendingId/markInProgress/getById` |
| 18 | `RepositorioEscaneos.sincronizarDesdeBackend()` | RepositorioEscaneos.kt:393 | Legacy test-only |
| 19 | `RepositorioUrlsBloqueadas.sincronizarDesdeBackend()` | RepositorioUrlsBloqueadas.kt:142 | Legacy test-only |
| 20 | `EstadisticasRespuesta` DTO | modelos.py:171 | DTO vivo en código pero endpoint que lo usa es muerto (#7) |
| 21-23 | Ramas "modo normal" | historial.py / bloqueadas.py / denuncias.py | Ver Eje 1 arriba |

### 🟢 BAJO (2 elementos — campos escritos no leídos)

| # | Símbolo | Archivo:línea | Razón |
|---|---|---|---|
| 24 | `UrlCatalogoEntity.ultimaProbabilidad` | UrlCatalogoEntity.kt:44 | Escrito en `registrarLocal`, no leído por `Pipeline.resumenCacheDuplicado` |
| 25 | `SyncStateEntity.ultimaSincronizacionExitosa` | SyncStateEntity.kt:29 | Escrito por `actualizar`/`actualizarTimestamp`, nunca leído |

### ✅ Confirmado vivo (no eliminar)

- `backend/api/index.py` (entrypoint Vercel), `GET /` y `GET /salud` (infra/healthcheck)
- Todos los DTOs Pydantic y Kotlin (12 métodos `suspend` de `ClienteBackend.kt` con callers verificados)
- `train_canine_s.py` → `export_onnx.py` → `export_tflite.py` (pipeline ML standalone, artefacto `.tflite` consumido por Android)
- `backend/app/base_datos.py` — todos los helpers vivos
- Migraciones Room invocadas por `BaseDatosSeguridad`

---

## EJE 3 — BUGS DE LÓGICA DE SYNC (Android)

### 🔴 CRÍTICOS

**C1 — Race condition: SyncWorker push vs `eliminarLocal` → fila fantasma que resurrecta en PULL**

- **Archivo**: `RepositorioEscaneos.kt:304-333` + `529-614`; mismo bug en `RepositorioUrlsBloqueadas.kt:108-136` + `253-320`
- El `backend.registrarEscaneo()` se ejecuta FUERA de la tx Room. Ventana entre POST-return y `db.withTransaction { reKey; borrarPorId }`:
  1. SyncWorker recibe `id=U-B` del servidor
  2. Usuario dispara `eliminarLocal(U-A)` — fila sigue `dirty=true` (re-key pendiente) → rama `if (fila.dirty)` → borra op CREATE + fila
  3. SyncWorker: `reKey(U-A→U-B)` retorna 0 filas, `borrarPorId(op)` retorna 0 — tx vacía
  4. Servidor tiene U-B; cliente no tiene nada
  5. **Próximo PULL** inserta U-B con `dirty=false` → **fantasma reaparece**
- El comentario "phantom-rows fix" (líneas 290-303) no cubre push ya enviado.

**C2 — Pending ops marcadas `fallida` tras 3 reintentos transitorios → pérdida de datos**

- **Archivo**: `SyncWorker.kt:269-303`, `MAX_INTENTOS_OP=3`, backoff `EXPONENTIAL 10s`
- Secuencia: Run 1 (intentos 0→1, falla) → +10s Run 2 (1→2, falla) → +30s Run 3 (2→3, falla) → +70s Run 4 (claim 3→4, check `4>3` → `marcarFallida`)
- En **~70 segundos** de red flaky, el op queda `fallida=1` permanente. No existe deflación de `intentos` tras éxito. CREATE del usuario descartado → **pérdida silenciosa**.

### 🟠 ALTOS

**A1 — Cursor pagination: `max(updated_at)` + backend `>=` → refetch eterno de fila límite + pérdida por offset con inserts concurrentes**

- **Archivo**: `RepositorioEscaneos.kt:477-480`, `UrlsBloqueadas.kt:216-219`, `Denuncias.kt:171-174`. Backend confirma `>=` en `ClienteBackend.kt:296`.
- (a) Si el último batch del run deja cursor=T y existe sólo 1 fila con `updated_at=T` sin más filas `>T`, esa fila se re-trae en cada run → cursor nunca avanza → refetch infinito cada 30s.
- (b) Dentro de un worker-run, batches 2-5 usan cursor FIJO (C0) + offset creciente. Si el backend recibe inserts/modificaciones entre batches, el offset pagination se corrompe: una fila que debería estar en posición 1000 se desplaza a 1001, y `actualizarCursor` salta a `max(updated_at)` más alto → la fila desplazada se pierde permanentemente.

**A2 — Manejo de 409 Conflict: `marcarSincronizado` sin re-key → dos filas con IDs distintos tras PULL**

- **Archivo**: `RepositorioEscaneos.kt:594-602`, `UrlsBloqueada.kt:305-311`, `Denuncias.kt:262-268`
- El 409 handler hace `marcarSincronizado(op.idLocal, ahora)` sin tocar `id`. El servidor tiene el row con id=U-Z, el local con id=U-A. PULL trae U-Z → `INSERT OR REPLACE` no colisiona (PK distinta) → local queda con 2 filas.
- **denuncias**: `observarTodas()` no dedup → **dos denuncias visibles**
- **urls_bloqueadas**: `observarTodos()` no dedup → **URL bloqueada dos veces en UI**
- **escaneos**: `observarTodosUnicos()` dedup por `urlLimpia` con `MAX(creadoEnMillis)` esconde una, pero `observarReescaneos` la muestra como fantasma

**A3 — Dedup de pending_ops inefectivo: `idLocal` es UUID fresh por llamada → `findExisting` siempre retorna null**

- **Archivo**: `RepositorioEscaneos.kt:208-241` (sin dedup), `UrlsBloqueadas.kt:56-94`, `Denuncias.kt:48-91`
- `idLocal = UUID.randomUUID()` se genera dentro de cada llamada, luego `findExisting(tabla, idLocal, "CREATE")` busca ese UUID recién creado → siempre null. El dedup es no-op.
- Doble-tap UI → 2 escaneos/URLs/denuncias creadas en backend (si no hay unique constraint server-side)

**A4 — SyncWorkers simultáneos (one-shot + periodic) hacen `markInProgress` sobre mismo op → `intentos` duplica → fallida prematura**

- **Archivo**: `MediadorSincronizacion.kt:138-142` (one-shot `NOMBRE_TRABAJO`) vs `:193-197` (periodic `NOMBRE_TRABAJO + "_periodica"`) — nombres distintos permiten concurrencia
- Dos workers reclaman el mismo op → `intentos +2` por falla en lugar de +1. Combinado con C2, ops marcadas fallida en ~35s en lugar de 70s.

**A5 — Crash POST-exitoso pre-rekey → estado op+fila inconsistente**

- **Archivo**: `RepositorioEscaneos.kt:543-565`, `UrlsBloqueadas.kt:253-320`, `Denuncias.kt:207-277`
- Si el proceso muere tras POST exitoso pero antes de `db.withTransaction { reKey; borrarPorId }`: servidor tiene U-B, local tiene fila U-A dirty + op CREATE intacto. Siguiente run reprocesa el op → POST duplicado → si backend no idempotente, crea U-C → 2 filas fantasma en servidor.

### 🟡 MEDIOS

**M1 — `eliminarLocalPorUrlLimpia` hereda el race phantom-resurrect de C1**

- **Archivo**: `RepositorioEscaneos.kt:354-382` — mismo `if (fila.dirty)` → mismo race window

**M2 — `limpiarHuerfanos` documentado como "llamado por SyncWorker" pero SyncWorker nunca lo invoca** ✅ RESUELTO

- **Archivo**: `SyncWorker.kt` (sin calls); definido en `RepositorioEscaneos.kt:486-511`, `UrlsBloqueadas.kt:225-238`, `Denuncias.kt:180-193`
- KDoc miente. Comentado en SyncWorker: "No hay orphan cleanup — los tombstones se manejan via `deleted_at`". Si el backend tiene TTL en tombstones, filas borradas hace mucho quedan como zombies locales perpetuos.
- **Fix**: `procesarDeltaTabla` ganó el parámetro `limpiarHuerfanos: (suspend (List<String>) -> Unit)? = null` (SyncWorker.kt:298-357). `procesarDeltaPulls` lo cablea vía method references en las 3 tablas (`repoUrls::limpiarHuerfanos`, `repoEscaneos::limpiarHuerfanos`, `repoDenuncias::limpiarHuerfanos`). Se invoca SOLO tras un pull `pullCompleto=true` (todas las páginas) para no borrar rows que existen en páginas aún no fetchadas. KDoc actualizado.
- **Verificación**: cobertura de `limpiarHuerfanos` ya existente en `OrphanCleanupTest.kt`; cableado compilado + suite Android verde.

**M3 — `eliminarLocal` encola DELETE op aunque la fila no exista localmente** ✅ RESUELTO

- **Archivo**: `RepositorioEscaneos.kt:320-331`, `UrlsBloqueadas.kt:128-134`
- `fila == null` va al `else` → encola DELETE para UUID que no existe local → POST al backend da 404 → tratado como success. Wasteful pero no data-loss.
- **Fix**: early-return si `fila == null` en `eliminarLocal` (RepositorioEscaneos.kt:267-269), `eliminarLocalPorUrlLimpia` (skip row desaparecido, :324-326) y `desbloquearLocal` (RepositorioUrlsBloqueadas.kt:126-128). Ya no se encolan DELETE ops huérfanos ni se reintentan sin efecto.
- **Verificación**: `EliminarIdInexistenteTest.kt` (5 tests) — id inexistente → cola vacía + fila sana intacta; controles positivos (fila synced → SÍ encola DELETE). Suite Android verde.

**M4 — DELETE path no deduplica (como sí intenta CREATE con `findExisting` inútil)**

- **Archivo**: `RepositorioEscaneos.kt:303-382`, `UrlsBloqueadas.kt:108-136`
- Múltiples taps → múltiples DELETE ops. Backend idempotente vía 404 pero wasteful.

**M5 — FK categorías-denuncia: retry loop infinito si categoría eliminada en backend** ✅ RESUELTO

- **Archivo**: `RepositorioDenuncias.kt:156-178`, `DenunciaEntity.kt:30-36` (FK RESTRICT)
- `aplicarBatchDenuncias` → `insertarTodos` falla FK si `idCategoria` no existe local. `SyncWorker.kt:167-176` procesa categorías primero pero no aborta si fallan → denuncias stuck en retry infinito.
- **Fix**: `procesarDeltaPulls` (SyncWorker.kt:233-249) ahora setea `categoriasOk=false` si el pull de categorías falla transitoriamente no-auth (5xx/429/sin-red), y bajo ese flag SKIPEA el pull de denuncias este run (skip con `Log.w` en :271-280). URLs y escaneos sí sincronizan (no dependen de la FK). Lógica extraída a función pura `debeSaltarPullDenuncias(resultadoCategorias)` (SyncWorker.kt, top-level, estilo `decidirResultadoPull`) — 401/403 retornan authError antes (logout, no skip de denuncias por defensividad).
- **Verificación**: `DebeSaltarPullDenunciasTest.kt` (9 tests) — Exitoso→no salta; 500/503/429/sin-red→salta; 401/403/400/422→no salta. Suite Android verde.

### 🟢 BAJOS

- **B1**: `procesarDeltaTabla` re-lee cursor una vez al inicio; recuperación post-crash consistente pero documentación confusa.
- **B2**: `sincronizarDesdeBackend` legacy wrapper usa epoch ignorando cursor persisted → re-descarga todo si se invoca.
- **B3**: `RepositorioCategorias.sincronizarDesdeBackend` hace full upsert sin gestión de tombstones → categorías eliminadas en backend quedan perpetuas localmente.
- **B4-B6**: wrappers legacy, doc mismatches menores.

---

## EJE 4 — BUGS DE LÓGICA DE NEGOCIO (Backend)

| # | Sev | Bug | Archivo:línea | Descripción |
|---|---|---|---|---|
| B1 | 🔴 ALTO | **TOCTOU sin transacción en `bloquear_url`** ✅ RESUELTO | bloqueadas.py:72-134 | SELECT+INSERT/UPDATE sin tx: dos llamadas concurrentes misma URL → `23505` no capturado → HTTP 500. **Verificado en working tree**: ya usa `INSERT ... ON CONFLICT DO NOTHING` en una tx única (bloqueadas.py:160-178); sin ventana TOCTOU; 23505 ya no ocurre. |
| B2 | 🟡 MEDIO | **`amenazas` no cuenta `SOSPECHOSO`** ✅ RESUELTO | estadisticas.py:30-35 | Cuenta sólo `es_malicioso=true`; escaneos `SOSPECHOSO` no se contabilizan como amenaza. Sesgo estadístico. **Verificado en working tree**: `estadisticas.py` fue eliminado por código muerto (router vacío de 9 líneas) → el endpoint ya no existe; nada que corregir. |
| B3 | 🟢 BAJO | **Docstrings contradictorios** ✅ RESUELTO | historial.py:185-190 / 79-83 | `existe_url` menciona campos removidos; `listar_escaneos` dice "NO aplica paginacion en modo delta" pero el código sí aplica LIMIT/OFFSET. **Fix**: `existe_url` ya coherente desde tandas previas (modelo seguro sin campos sensibles, historial.py:272-296); docstring de `listar_escaneos` corregido — el modo delta SÍ pagina (LIMIT/OFFSET + keyset). |

---

## RESUMEN EJECUTIVO

| Categoría | 🔴 Alto/Crítico | 🟡 Medio | 🟢 Bajo | **Total** |
|---|---|---|---|---|
| Contrato backend↔Android | 4 endpoints huérfanos | 3 ramas dead | — | **7** |
| Código muerto | 13 | 10 | 2 | **25** |
| Bugs de sync Android | 7 (C1-C2, A1-A5) | 5 (M1-M5) | 6 | **18** |
| Bugs backend | 1 (TOCTOU) | 1 (amenazas) | 1 (docs) | **3** |
| **Total** | **25** | **19** | **9** | **53** |

### Prioridades de acción recomendadas

1. **C1 + C2** (críticos de sync) — causa pérdida de datos y UX corrupta bajo condiciones normales de uso (red flaky, usuario delete mientras sync corre)
2. **TOCTOU backend** (`bloqueadas.py`) — fix rápido, alto impacto (HTTP 500 bajo carga concurrente)
3. **A1** (paginación delta) — refetch eterno drena batería; pérdida de filas si backend recibe escrituras durante sync
4. **A2 + A3** — duplicados visibles en UI tras 409 o doble-tap
5. **Código muerto bloque ALTO (13 elementos)** — eliminación segura sin tocar tests

---

## ESTADO DE CIERRE (fixes aplicados y verificados)

| Hallazgo | Fix | Verificación |
|---|---|---|
| **M2 — limpiarHuérfanos sólo con pull completo** | `procesarDeltaTabla` gana parámetro `limpiarHuerfanos`, se llama con `true` sólo cuando el pull fue completo; guard `pullCompleto` evita borrar filas no re-sincronizadas; KDoc corregido | `OrphanCleanupTest` + suite Android verde |
| **M3 — DELETE op huérfano si fila no existe** | Early-return si `fila == null` en `eliminarLocal` / `eliminarLocalPorUrlLimpia` / `desbloquearLocal` | `EliminarIdInexistenteTest` (5 tests) + suite Android verde |
| **M5 — retry loop infinito denuncias si categoría ausente** | Flag `categoriasOk` + función pura `debeSaltarPullDenuncias`; skip del pull de denuncias en fallo transitorio no-auth | `DebeSaltarPullDenunciasTest` (9 tests) + suite Android verde |
| **B1 backend — TOCTOU `bloquear_url`** | Verificado ya resuelto en working tree: `INSERT ... ON CONFLICT DO NOTHING` en tx única | pytest (59 passed, 1 xfailed) |
| **B2 backend — `amenazas` no cuenta SOSPECHOSO** | Verificado resuelto: `estadisticas.py` eliminado (código muerto) | pytest |
| **B3 backend — docstrings contradictorios** | Docstring `listar_escaneos` corregido (modo delta SÍ pagina); `existe_url` ya coherente | pytest |

**Recomendaciones pendientes (no aplicadas en esta tanda)**: C1/C2/A1/A2/A3 y código muerto ALTO — requieren cambios de mayor alcance (rework de paginación delta, estrategia de merge 409) fuera del alcance de esta pasada de bugs puntuales. Bajos EJE 3 (B1-B6: wrappers legacy, doc mismatches) siguen abiertos como deuda menor.
