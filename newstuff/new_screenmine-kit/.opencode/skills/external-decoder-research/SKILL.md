---
name: external-decoder-research
description: Descodificar H.264/AAC fora do Chromium (WaterMedia/ffmpeg) para sítios que o mcef-modern não toca. Usar antes de propor ou começar essa rota, ao avaliar licenças de outros mods de vídeo, ou quando o utilizador trouxer links de mods/ffmpeg.
---
# Descodificar fora do Chromium (opção (d) do `codecs.md`)

Análise feita a 2026-10-16 sobre os repos `SrRapero720/watermedia`, `NGoedix/VideoPlayer` e `SrRapero720/watervision`. Etiquetas em `evidence-labels`.

## O que existe
| Projeto | O que é | Licença | Serve para |
|---|---|---|---|
| **WaterMedia** 3.0.0.24 (BETA) | motor multimédia: ffmpeg 8.1.2 (JavaCPP) + motores GL/Vulkan/OpenAL + resolvers de plataformas (Medal, TikTok, Twitch, Kick, Streamable, Imgur…) | **DPS (NC) v1.0.0** — só não-comercial | **única base séria** para a rota (d), como dependência externa |
| **VideoPlayer** (NGoedix) | mod que toca vídeo em GUI; depende do WaterMedia 2.1.22 | **All rights reserved** | só referência de uso; **não copiar código** |
| **WaterVision** (SrRapero720) | `/video` em ecrã inteiro; WaterMedia 2.1.30 | **PolyForm Strict 1.0.0** | idem; **não copiar código** |
| mp4-player (CurseForge) | sem repo analisado | — | não avaliado |
| gyan.dev / ffmpeg.org | binários e página de download do ffmpeg | LGPL/GPL conforme a build | ferramenta local de conversão (ver `codec-probe`) |
| x264 (free-codecs) | pack de codecs do Windows | — | **não ajuda**: o Chromium traz o ffmpeg dele **[I]**; um pack de sistema não o altera |

## Factos **[V]** (lidos no repo do WaterMedia)
- ffmpeg 8.1.2 **LGPL-3.0-or-later, compilado sem `--enable-gpl`, x264 nem x265**; binários num jar à parte (*WATERMeDIA: Binaries*); Windows x86_64 ✅, Windows aarch64 ⛔.
- Versões publicadas: `1.18.2, 1.19.2, 1.20.1, 1.21.1, 1.21.11, 26.1.2, 26.2`. **26.3 não está na lista.** A licença diz que omitir uma versão não é recusa explícita.
- Motores: `GLEngine` ("auto-contido", repõe o estado GL do Minecraft) e `VKEngine` (precisa de um mixin em `VulkanDevice` a implementar `VKContext`; o README descreve o padrão). `ALEngine` para som.
- API: `MediaAPI.getMrl(URI)` → `MRL`; `MediaAPI.createPlayer(mrl, gfx, sfx)` → `MediaPlayer` (play/pause/seek/volume, `texture()` por frame).
- Existem classes `MedalPlatform`, `TikTokPlatform`, `TwitchPlatform`, `KickPlatform`…; o README refere resolvers "executable-backed" (yt-dlp descarregado). Atenção: o **WaterMedia descarrega binários em runtime** (yt-dlp, rustypipe-botguard).

## Hipóteses por provar **[H]/[I]**
- **[I]** O descodificador H.264 e o AAC do libavcodec são nativos (LGPL, não precisam de x264/x265, que são encoders): a build do WaterMedia deve tocar H.264+AAC. **Prova:** uma corrida com um `.mp4` H.264+AAC.
- **[H]** Que backend gráfico a 26.3 usa por omissão (GL ou Vulkan). Decide qual motor usar. Confirma com `javap`/log antes de escolher; não adivinhes o método.
- **[H]** Se o WaterMedia 3.0.0.24 carrega em 26.3 sem alterações (não está publicado para 26.3).
- **[H]** Se os resolvers de Medal/TikTok/Twitch funcionam hoje (dependem de sites que mudam).

## Licença — o ponto que decide
1. **Só não-comercial.** O ScreenMine (AGPL, gratuito) cumpre; vender ou monetizar o mod com WaterMedia dentro **não**.
2. **Mods dependentes não podem embeber nem redistribuir o WaterMedia** (§2.1): `compileOnly` + exigir o utilizador instalá-lo, como já se faz com o `mcef-modern`. Nunca meter o jar no nosso.
3. **§4.3 Não-concorrência (risco real):** é proibido usar o software num produto em "Direct Competition" com um produto do licenciante que também use o WaterMedia — o **WaterFrames** (ecrãs/molduras com media no mundo, do mesmo autor). Duas condições: (1) o nosso produto pode substituí-lo para usos e utilizadores substancialmente iguais; (2) a última versão estável dele funciona nativamente no ambiente (aqui: Fabric 26.3). Um ecrã no mundo com vídeo **cheira** a sobreposição, ainda que o ScreenMine seja um browser completo. **[H] Não sei como seria decidido. Não sou advogado. Pergunta ao autor antes de publicar** (e o mesmo pedido pendente ao autor do CoScreen sobre `-only`/`-or-later`).
4. **Compatibilidade com AGPL:** como dependência externa separada não há mistura de código. Se alguma vez copiares código do WaterMedia, **para**: a DPS(NC) não é compatível com AGPL.
5. **Patentes de H.264:** o ScreenMine não redistribuiria ffmpeg, mas isso não resolve patentes — é uma questão do utilizador/WaterMedia. Regista para publicação.

## Custo e desenho (esboço, não plano aprovado)
- Rota híbrida: Chromium para páginas; **MediaPlayer** só quando há um URL de média que o Chromium recusa (`video.error.code=4` na sonda, ou URL direto `.mp4` H.264, ou plataformas que o WaterMedia resolve).
- Duas fontes de textura no mesmo ecrã (browser ou `MediaPlayer`), com áudio por `ALEngine` e **falloff por distância** (a lógica do ScreenMine, não a do WaterMedia).
- Sincronização de playback (`PlaybackSync`) precisa de um segundo caminho: seek/pause/volume do `MediaPlayer`.
- Páginas que embutem o vídeo (TikTok dentro de uma página) não se interceptam trivialmente; só ajuda se o utilizador abrir o URL da plataforma diretamente no modo media.
- Risco: dois pipelines de vídeo, uma dependência BETA e de licença restritiva, e binários descarregados em runtime (ver `security-hardening`).

## Ordem recomendada (do mais barato ao mais caro)
1. **Servidor próprio:** transcodificar para AV1/VP9 + Opus (já documentado em `codec-probe`).
2. **Ficheiros locais:** converter com ffmpeg para `.webm` e servir por HTTP local (`codec-probe`).
3. **Spike mínimo com WaterMedia:** um comando de debug que abre um `.mp4` H.264 numa textura do ecrã, sem sync. Responde às três hipóteses acima com uma corrida (`playtest-handoff`).
4. Só depois decidir integração completa — **e só depois de falar com o autor sobre §4.3**.

## Regras
- Nunca copies código de projetos "All rights reserved", PolyForm Strict ou DPS(NC) para o ScreenMine. Ler a API pública e a documentação é ok.
- Verifica versões e licenças nos repos reais antes de afirmar (`dependency-check`); este ficheiro tem data.
- Links/hostnames pessoais do utilizador não entram aqui (`repo-hygiene-privacy`).
