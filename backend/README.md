# QR Guardian — Backend API

Backend REST para la app Android **QR Guardian**. Deteccion de URLs maliciosas
incrustadas en codigos QR.

> **Nota:** El modelo ML (CANINE-S) no esta integrado aun. El backend maneja
> autenticacion, historial de escaneos, URLs bloqueadas y denuncias. La
> inferencia se anadira en una fase posterior.

---

## Stack

| Componente | Tecnologia |
|---|---|
| Framework | **FastAPI** 0.115 |
| Base de datos | **Neon** (PostgreSQL serverless) — proyecto Edgar, BD `qr_guardian` |
| Driver async | **asyncpg** 0.30 |
| Validacion | **Pydantic** 2.10 |
| Servidor | **uvicorn** |

---

## Estructura

```
backend/
├── .env                    ← Variables de entorno (NO commitear)
├── .env.example            ← Template de variables
├── requirements.txt
└── app/
    ├── __init__.py
    ├── main.py             ← App FastAPI + lifespan
    ├── config.py           ← Ajustes (lee .env)
    ├── base_datos.py       ← Pool de conexiones asyncpg
    ├── modelos.py          ← Esquemas Pydantic (entrada/salida)
    └── routers/
        ├── __init__.py
        ├── auth.py         ← Registro + login por usuario/password
        ├── historial.py    ← CRUD de escaneos
        ├── bloqueadas.py   ← CRUD de URLs bloqueadas
        └── denuncias.py    ← Denuncias + categorias
```

---

## Base de datos (Neon — proyecto Edgar)

**Conexion:**
- Proyecto: `solitary-bonus-36970102` (Edgar)
- BD: `qr_guardian`
- Region: `azure-eastus2`

**Tablas:**

| Tabla | Descripcion |
|---|---|
| `usuarios` | Usuarios con `nombre_usuario` + `password` (bcrypt) + `token_api` |
| `historial_escaneos` | Historial de escaneos de QR (url, probabilidad, nivel_alerta) |
| `urls_bloqueadas` | URLs bloqueadas por el usuario |
| `denuncias_url` | Denuncias de URLs maliciosas |
| `categorias_denuncia` | Categorias de denuncia (solo **Phishing** por ahora) |

---

## Instalacion

### 1. Crear entorno virtual e instalar dependencias

```bash
cd backend
python -m venv venv

# Windows
venv\Scripts\activate
# Linux/Mac
source venv/bin/activate

pip install -r requirements.txt
```

### 2. Configurar variables de entorno

```bash
cp .env.example .env
# Editar .env con la URL de conexion a Neon
```

### 3. Iniciar el servidor

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### 4. Verificar

- Raiz: http://localhost:8000/
- Swagger UI: http://localhost:8000/docs
- Healthcheck: http://localhost:8000/salud

---

## API Endpoints

### Auth

| Metodo | Ruta | Descripcion | Auth |
|---|---|---|---|
| POST | `/auth/registrar` | Registra un usuario con `nombre_usuario` + `password` y devuelve `token_api` | No |
| POST | `/auth/login` | Autentica con `nombre_usuario` + `password` y devuelve `token_api` | No |

**Ejemplo — registrar:**
```json
POST /auth/registrar
{
    "nombre_usuario": "rena_99",
    "password": "s3cret-password",
    "correo": "usuario@email.com"
}

Respuesta:
{
    "id_usuario": "uuid-...",
    "token_api": "token-seguro-...",
    "nombre_usuario": "rena_99",
    "correo": "usuario@email.com",
    "creado_en": "2026-07-24T..."
}
```

**Ejemplo — login:**
```json
POST /auth/login
{
    "nombre_usuario": "rena_99",
    "password": "s3cret-password"
}

Respuesta: igual que /auth/registrar (devuelve el mismo token_api del registro).
```

### Escaneos (Historial)

| Metodo | Ruta | Descripcion | Auth |
|---|---|---|---|
| POST | `/escaneos` | Registra un nuevo escaneo | Token |
| GET | `/escaneos?filtro=todos` | Lista historial (todos/seguros/maliciosos) | Token |
| GET | `/escaneos?modificados_desde=ISO8601` | Delta sync: modificados desde fecha (incluye tombstones) | Token |
| GET | `/escaneos/count` | Conteo de escaneos por filtro (todos/seguros/maliciosos) | Token |
| GET | `/escaneos/existe-url?url_limpia=...` | Dedup: consulta cache maestro `urls_catalogo` | Token |
| GET | `/escaneos/{id}` | Obtiene un escaneo por ID | Token |
| DELETE | `/escaneos/{id}` | Elimina un escaneo | Token |

### URLs Bloqueadas

| Metodo | Ruta | Descripcion | Auth |
|---|---|---|---|
| GET | `/urls-bloqueadas` | Lista URLs bloqueadas | Token |
| GET | `/urls-bloqueadas?modificados_desde=ISO8601` | Delta sync: modificados desde fecha (incluye tombstones) | Token |
| POST | `/urls-bloqueadas` | Bloquea una URL | Token |
| DELETE | `/urls-bloqueadas/{id}` | Desbloquea una URL | Token |

### Denuncias

| Metodo | Ruta | Descripcion | Auth |
|---|---|---|---|
| GET | `/denuncias/categorias` | Lista categorias (solo Phishing) | No |
| POST | `/denuncias` | Crea una denuncia | Token |
| GET | `/denuncias` | Lista denuncias del usuario | Token |
| GET | `/denuncias?modificados_desde=ISO8601` | Delta sync: modificados desde fecha (incluye tombstones) | Token |
| DELETE | `/denuncias/{id}` | Elimina (soft-delete) una denuncia | Token |

---

## Autenticacion

Todos los endpoints protegidos requieren el token en el **header** estandar REST:

```
Authorization: Bearer <token_api>
```

> **Compatibilidad retroactiva:** el backend acepta tambien el token como
> query param `?token_api=...` para no romper clientes antiguos que aun lo
> mandan asi. Los clientes nuevos **deben** usar el header — el query param
> puede quedar registrado en logs de acceso o capas de cache intermedias,
> mientras que el header `Authorization` no.

El backend verifica el token contra la tabla `usuarios` y devuelve el
`id_usuario` (UUID) asociado.

### Healthcheck (`/salud`)

```
GET /salud
```

Devuelve:
- **200 OK** cuando la conexion a la base de datos Neon responde.
- **503 Service Unavailable** cuando la BD esta caida (los monitores de
  uptime pueden usar el codigo HTTP para alertar).

---

## Modelo ML (futura integracion)

El backend esta diseño para recibir los resultados del modelo CANINE-S
que corre **on-device** en Android. La app envia el resultado del analisis
(`probabilidad`, `nivel_alerta`, `delegado`) al endpoint `POST /escaneos`
para almacenarlo en el historial.

El backend **no** hace inferencia — solo almacena y sirve los resultados.
