# Wake UP! BPM

Recreación independiente de un detector de BPM en tiempo real para Android 8–16.

## Funciones

- Detección mediante el micrófono entre 60 y 200 BPM.
- Promediado temporal para evitar saltos en la lectura.
- Historial gráfico, nivel de estabilidad y control iniciar/detener.
- Interfaz adaptable a orientación vertical y horizontal.
- Lectura precisa con dos decimales.
- Motor rápido con análisis de graves, envolvente de ataques, autocorrelación y ajuste submuestra.
- Inicio automático al abrir o volver a la aplicación.
- Presentación de BPM, estabilidad y gráfica cada 0,5 segundos.
- Motor 3.0 recreado con FFT solapada de 2048 puntos, flujo espectral multibanda, correlación, seguimiento de candidatos y bloqueo por consenso.
- La lectura aparece únicamente tras detectar seis pulsos coherentes; localización del golpe a nivel de muestra.
- Bloqueo por consenso: ignora lecturas aisladas y solo cambia de rango tras confirmación continuada.
- Identidad visual Wake UP! en rojo, blanco y azul.
- Implementación Java pura: compatible con ARM64, x86_64 y dispositivos con páginas de 16 KB.
- `targetSdk 36` para Android 16.

## Compilación

Abrir la carpeta en Android Studio, usar JDK 17 y ejecutar **Build > Generate App Bundles or APKs > Generate APKs**.

La primera versión debe calibrarse con música real. Los resultados dependen del volumen, el ruido ambiente y la claridad de los golpes rítmicos.
