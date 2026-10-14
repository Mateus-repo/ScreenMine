---
name: fabric-loom-setup
description: Configurar Gradle, Fabric Loom, versões e fabric.mod.json para a versão destino do Minecraft. Usar na fase de setup e quando o build falha por versões.
---
# Fabric Loom setup

## Fontes de verdade
- Versões de Loom, Fabric Loader, Fabric API e Java: página oficial do Fabric (develop/template) e o Maven do Fabric. **Não inventes números.**
- Confirma a versão de Java necessária para a versão destino (pode ter subido) e o `JAVA_HOME`.

## Passos
1. Atualiza o Gradle wrapper para a versão exigida pelo Loom.
2. Em `gradle.properties`: `minecraft_version`, `loader_version`, `fabric_version`, `mod_version`.
3. Em `build.gradle`: plugin Loom da versão certa; `sourceCompatibility`/`release` = versão de Java.
4. **Mappings:** verifica se a versão destino precisa de mappings (Mojang/Yarn) ou se já vem sem ofuscação. Segue o template oficial para essa versão.
5. `fabric.mod.json`: `depends.minecraft` (intervalo certo), `depends.fabricloader`, `depends.fabric-api`, `environment: client` se for client-only.
6. `./gradlew build --refresh-dependencies` com um mod vazio **antes** de trazer código.

## Client-only
- `"environment": "client"` e usa o entrypoint `client`.
- Não registes nada de servidor. O mod não deve ser exigido pelo servidor.

## Problemas comuns
- *Could not resolve*: versão inexistente → vê o Maven/Modrinth.
- *Unsupported class file major version*: Java errado.
- Mixins não aplicam: `refmap`/`mixins.json` desatualizado → ver `mixin-migration`.
