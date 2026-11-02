#!/bin/bash
# Ejecuta la aplicación JavaFX desde la raíz del proyecto.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# Este script usa la ruta local del JDK del entorno original.
# En otros equipos, ejecuta directamente `mvn javafx:run`.
export JAVA_HOME="$HOME/.jdks/temurin-21.0.13/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

mvn javafx:run
