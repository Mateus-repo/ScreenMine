# Teste do ficheiro local — o caminho mais barato para provar o codec

**Porquê isto primeiro.** Não precisas do Jellyfin, nem de transcodificação, nem
de um mp4 H.264 para saber se o alvo de transcodificação que queres usar
resulta. Convertes um ficheiro teu e abres no ecrã. Se tocar, o caminho está
validado antes de mexeres em alguma coisa no servidor.

## 1. Converter

O teu ficheiro é **AV1 + AAC**. O mod toca AV1 e não toca AAC, por isso **o vídeo
copia-se e só o áudio se converte**:

```powershell
ffmpeg -i entrada.mp4 -map 0:v:0 -map 0:a:0 -c:v copy -c:a libopus -b:a 128k saida.webm
```

`-c:v copy` é o ponto: não reencoda vídeo nenhum. Se algum dia tiveres um
ficheiro **H.264** (que é o que o mod recusa), reencoda o vídeo para VP9:

```powershell
ffmpeg -i entrada.mp4 -c:v libvpx-vp9 -crf 32 -b:v 0 -c:a libopus -b:a 128k saida.webm
```

Isto é lento. Para testar rápido, baixa a resolução:

```powershell
ffmpeg -i entrada.mp4 -vf scale=640:-2 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 `
  -c:a libopus -b:a 64k saida.webm
```

**Confirma o que saiu** antes de abrires no jogo:

```powershell
ffprobe -v error -show_entries stream=index,codec_name,codec_type `
  -of default=noprint_wrappers=1 saida.webm
```

Tens de ver `codec_name=av1` (ou `vp9`) **e** `codec_name=opus`. Se aparecer
`aac`, o `-map 0:a:0` não pegou no áudio certo.

## 2. Servir por HTTP

`file://` está na lista de coisas a bloquear, e o browser não o quer de
qualquer maneira. Servidor local, na pasta do ficheiro:

```powershell
python -m http.server 8000
```

Ou, se não tiveres Python, o que já vem com o Java e portanto já tens:

```powershell
npx --yes http-server -p 8000
```

## 3. Abrir no mod

`M` para spawn, depois na barra de endereço do overlay `B`:

```
http://localhost:8000/saida.webm
```

## 4. O que o resultado significa

| O que vês | O que concluíste |
|---|---|
| O vídeo toca | O bloqueio era **só o AAC**. O Jellyfin a transcodificar para Opus resolve o teu caso. |
| Erro, e `codec-probe` diz `av01+opus webm = probably` | O `.webm` não é o problema — provavelmente falta um content-range ou o servidor não suporta |
| Erro, e o probe diz `av01+opus webm = -` | Então o problema é o **contentor**, não os codecs. Tenta `.mp4` com AV1 + Opus |
| Nada, sem erro | Ver §5 |

## 5. O `error.code` — porque não toca

O browser tem uma resposta pronta e nós é que não a lemos. Para a ver agora,
**sem esperar por uma alteração do mod**, abre o developer console. Mas a forma
 limpa é o auto-relatório do agente, que já existe: liga `debug.codecs=1` e
procura no log

```
Select-String "agent report|ScreenMine/codecs" run\logs\latest.log
```

O que interessa:

- `media=0` → não há elemento de media. A página não tem `<video>` nenhum; o
  browser trata-o como uma transferencia e nao cria elemento nenhum.
- `media=1` e `vpaused=1` → o elemento existe e está parado. Falhou ao carregar.
- `MEDIA_ERR_SRC_NOT_SUPPORTED` (code 4) → **o browser recusou o formato**. É a
  resposta do codec.

Um `.webm` aberto directamente no browser **não cria um `<video>`** numa página
normal — o browser trata-o como uma transferência. Se for esse o caso, embrulha-o
numa página mínima:

```html
<!doctype html><meta charset=utf-8>
<video controls width=800 src="saida.webm"></video>
```

E abre `http://localhost:8000/teste.html`. Aí sim há `<video>` e o agente
reporta.

## 6. O que isto valida

- ✅ o alvo de transcodificação **VP9/AV1 + Opus** que o Jellyfin teria de
  produzir — antes de activares nada no servidor
- ✅ a sonda alargada, quando a tiveres
- ❌ **não** valida TikTok nem Twitch nem MedalTV. Esses servem H.264 e não tens
  servidor nenhum onde os transcodificar. Ver `codecs.md` §3e.

## Aviso

O `.webm` de teste fica em disco. Se for conteúdo com direitos, apaga-o depois.
E `python -m http.server` serve a pasta toda enquanto estiver a correr — não o
deixes aberto para a rede.
