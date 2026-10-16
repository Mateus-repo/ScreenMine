---
name: codec-probe
description: Diagnosticar porque um vídeo não toca no browser embebido (codecs, contentores, erro do elemento video, servidor próprio). Usar quando aparece "media is not supported by this client" ou quando um sítio novo falha.
---
# Diagnóstico de codecs e vídeo

## O que já está provado (não reabrir)
- **[V]** Chromium do `mcef-modern` 0.3.3 (CEF 146, binários `jcefbuild` 1.0.70): `canPlayType` vazio para **H.264, H.264 High, AAC, HEVC**; `probably` para **VP9, AV1, Opus, Vorbis**; `MediaSource=yes`. Corrida de 2026-10-15 12:55 (`codecs.md` §1b).
- **[V]** Padrão: YouTube serve VP9+Opus ✅; MedalTV/TikTok/Twitch servem H.264+AAC ❌.
- **[V]** Não existe variante com codecs no Maven Central (`me.friwi`, 14 artefactos, natives só por plataforma); `MCEFApi` não aceita outro binário; `checkInstallation` não verifica hashes das DLLs (`codecs.md` §3c–3d).
- **[V]** `cinemamod/mcef#28`: "irrelevante com 2.x" refere-se aos binários do CinemaMod, não ao JCEF; o repo do CinemaMod-Fabric dá 404.
- **[V]** DRM (Netflix, Disney+) não funciona por esta via.

## Como ler uma sonda
- `canPlayType` → `""` (não existe), `maybe`, `probably`. `MediaSource.isTypeSupported` → sim/não para MSE.
- Mede **combinações contentor+codecs**, não só codecs isolados. É o que os sítios servem.

## Extensão pendente da sonda (item 8 do `progresso.md`)
Acrescentar à lista de `CodecProbe.script()`:
```
video/mp4; codecs="av01.0.05M.08,mp4a.40.2"    ficheiro AV1+AAC (só o áudio falha)
video/webm; codecs="av01.0.05M.08,opus"        alvo de transcodificação AV1
video/webm; codecs="vp09.00.10.08,opus"        alvo de transcodificação VP9
video/webm; codecs="vp8,vorbis"
audio/mpeg   audio/flac   audio/mp4; codecs="mp4a.40.2"   audio/webm; codecs="opus"
```
E, **o mais útil**, capturar o erro real quando um `<video>` falha (JS, vai pelo `console` com prefixo `SMX|`, campos separados por `~`):
```js
document.addEventListener('error', function (e) {
  var v = e.target;
  if (!v || !v.tagName || !/^(VIDEO|AUDIO)$/.test(v.tagName)) return;
  var er = v.error || {};
  console.log('SMX|video.error.code=' + er.code + '~message=' + (er.message || '-')
    + '~src=' + String(v.currentSrc || v.src || '-').split('?')[0].slice(0, 120)
    + '~readyState=' + v.readyState + '~networkState=' + v.networkState);
}, true);   // captura, porque 'error' não faz bubble
```
Cuidados: corta o `src` (sem query string: tem tokens), limita o tamanho, regista uma vez por elemento.
Opcional: `navigator.mediaCapabilities.decodingInfo({type:'media-source', video:{contentType:'video/webm; codecs="av01.0.05M.08"', width:1920, height:1080, bitrate:4e6, framerate:30}})` devolve `supported/smooth/powerEfficient` (só em contexto seguro — **[H]** funciona no CEF embebido; verifica).

`CefBrowser.executeJavaScript` não devolve valor; o canal é `onConsoleMessage`. **Não uses `document.title`** (o agente reescreve-o a cada 250 ms). Ver `page-agent-channel`.

## Árvore de decisão
| Resultado | Causa provável | Ação |
|---|---|---|
| codec `-` na sonda | falta no Chromium | só servidor (transcodificar) ou build própria do JCEF |
| codec `probably` mas vídeo falha, `error.code=4` | contentor/perfil/nível não suportado | testar a combinação contentor+codec na sonda |
| `error.code=3` | falha de descodificação (resolução, nível, bitrate) | baixar a qualidade; ver `readyState` |
| `error.code=2` | rede, certificado inválido, redirect | testar o URL num browser normal; ver HTTPS/DDNS |
| sem `error`, mas não arranca | autoplay, sessão/login, `secureContext=no`, deteção de bot (o UA denuncia `MCEF-Modern`) | uma causa de cada vez, com log |

## Servidor próprio (Jellyfin e semelhantes)
- **[V]** É o cliente que escolhe o codec de destino da transcodificação. Se o ficheiro é AV1+AAC, o vídeo faz direct-play e só o **áudio** precisa de transcodificação — muito mais barato.
- Passos já registados em `progresso.md` → C-bis: ativar encoding AV1 no servidor, fixar o codec de vídeo preferido (não *Auto*), escolher Opus no áudio, testar primeiro com o ficheiro cujo vídeo já é AV1.
- Limite: AV1 por software em tempo real é pesado; encoders de hardware só em GPUs recentes.
- Para fechar a dúvida "o servidor não transcodifica": log do servidor em **Debug** e repetir. Sem isso é **[I]**.
- **Nunca** coloques o hostname, IP ou token do servidor em ficheiros versionados (`repo-hygiene-privacy`).

## Fora do âmbito (só com decisão explícita do utilizador)
- Build própria do JCEF com `proprietary_codecs=true ffmpeg_branding=Chrome`: 200–300 GB, horas, coerência de build completa. **Uso pessoal é zona cinzenta; redistribuir exige licenças de H.264.** Regista para publicação.
- Descodificar fora do Chromium (libvlc/ffmpeg → mesma textura): dois pipelines, trabalho que não se aproveita.
