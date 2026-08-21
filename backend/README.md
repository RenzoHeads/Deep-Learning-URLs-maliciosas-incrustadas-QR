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
| `usuarios` | Usuarios identificados por `auth0_user_id` (claim `sub` del JWT; provisioning JIT al primer login — migracion 012) |
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

### Autenticacion (Auth0)

El login/registro NO vive en este backend: la app Android usa **Auth0
Universal Login** (SDK `com.auth0.android:auth0`). Cada peticion
autenticada llega con el access token JWT en el header:

```
Authorization: Bearer <access_token_jwt>
```

`verificar_token` (app/dependencias.py) valida la firma RS256 contra el
JWKS del tenant (`AUTH0_DOMAIN`), el audience (`AUTH0_AUDIENCE`), issuer
y expiracion; despues resuelve el claim `sub` → `usuarios.auth0_user_id`,
creando la fila al primer login (provisioning JIT). Un 401
(`Token de API invalido`) indica JWT expirado/revocado o firma invalida.

Variables de entorno (ver `.env.example`): `AUTH0_DOMAIN`,
`AUTH0_AUDIENCE`, `AUTH0_ALGORITMOS=RS256`.

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

Todos los endpoints protegidos requieren el access token JWT de Auth0 en
el **header** estandar REST (el fallback `?token_api=` del auth legacy
fue eliminado junto con `/auth/login` y `/auth/registrar`):

```
Authorization: Bearer <access_token_jwt>
```

El backend verifica firma RS256 (JWKS del tenant), audience, issuer y
expiracion, y resuelve el claim `sub` contra `usuarios.auth0_user_id`
(ver "Autenticacion (Auth0)" arriba).

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
