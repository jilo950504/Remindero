# 🛸 Remindero

App Android que vigila una zona circular y suena una **alarma cósmica** con tu mensaje personalizado cuando sales de ella.

## Cómo usarla
1. Abre la app y toca **Usar mi ubicación actual** (ese será el centro).
2. Ajusta el **radio** con el slider (50 m – 3 km).
3. Escribe tu **mensaje personalizado**.
4. Toca **Activar vigilancia**. Si sales del círculo, suena la alarma y aparece tu mensaje.
5. **Silenciar** apaga la alarma (también desde la notificación). No vuelve a sonar hasta que regreses a la zona y vuelvas a salir.

## Compilar en GitHub
1. Crea un repositorio y sube **todo el contenido** de esta carpeta (incluida `.github`).
2. Ve a la pestaña **Actions** → se ejecuta *Build Remindero APK* (o dale **Run workflow**).
3. Al terminar, baja el artefacto **Remindero-APK** → dentro está el `.apk`.
4. Instálalo en tu teléfono (permite "instalar apps de origen desconocido").

## Notas
- Permisos: ubicación precisa y notificaciones.
- Para que no se duerma: Ajustes → Apps → Remindero → Batería → **Sin restricciones**.
- Requiere Android 8.0+ y el GPS encendido.
