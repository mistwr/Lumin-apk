# Lumin - Android APK

App wrapper (WebView) que carrega https://cinema-ruddy-kappa.vercel.app/

## Como gerar o APK

### 1. Criar um novo repositório no GitHub
- Vai a github.com → New repository
- Nome: `lumin-apk` (ou outro nome a tua escolha)
- Pode ser publico ou privado

### 2. Fazer upload de TODOS estes ficheiros, mantendo a mesma estrutura de pastas:

```
.github/workflows/build.yml
app/build.gradle
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/com/lumin/app/MainActivity.java
app/src/main/res/values/styles.xml
app/src/main/res/mipmap-mdpi/ic_launcher.png
app/src/main/res/mipmap-mdpi/ic_launcher_round.png
app/src/main/res/mipmap-hdpi/ic_launcher.png
app/src/main/res/mipmap-hdpi/ic_launcher_round.png
app/src/main/res/mipmap-xhdpi/ic_launcher.png
app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
app/src/main/res/mipmap-xxhdpi/ic_launcher.png
app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png
build.gradle
settings.gradle
gradle.properties
```

No GitHub mobile: usa "Add file → Upload files" e arrasta varios de cada vez, ou cria pasta por pasta com "Add file → Create new file" escrevendo o caminho completo (ex: `app/src/main/java/com/lumin/app/MainActivity.java`) — o GitHub cria as pastas automaticamente.

### 3. Esperar a compilação automatica
Depois do ultimo ficheiro ser enviado (commit feito), vai ao separador **Actions** do repositorio. Vais ver "Build Lumin APK" a correr (demora 3-5 minutos).

### 4. Descarregar o APK
Quando o workflow terminar com um check verde:
- Clica no workflow run
- Em baixo, na seccao "Artifacts", clica em **Lumin-APK** para descarregar um .zip
- Dentro do .zip esta o `app-debug.apk`
- Transfere esse ficheiro para o telemovel e instala (pode ser preciso ativar "Instalar de fontes desconhecidas" nas definicoes do Android)

## Notas
- Este APK e um wrapper simples: abre o site real dentro da app. Qualquer atualizacao que fizeres ao `index.html` no Vercel aparece automaticamente na app, sem precisar de gerar novo APK.
- So precisas de gerar novo APK se quiseres mudar o icone, nome, ou o URL que a app carrega.
- Para mudar o URL, edita a linha `SITE_URL` em `MainActivity.java`.
