# Kit opencode para portar mods de Minecraft (Fabric)

Pacote com **14 skills**, um **plugin de auto commit/push**, `AGENTS.md`,
`progresso.md` e `.gitignore`, pensado para portar um mod entre versões do Minecraft.

## Conteúdo
```
AGENTS.md                       regras + índice de skills (o opencode lê isto sempre)
progresso.md                    estado do port (o agente lê e atualiza)
README.md                       este ficheiro
.gitignore                      modelo para mods Fabric
.opencode/plugins/auto-commit.js   commit (e push) automático quando a sessão fica idle
.opencode/skills/<nome>/SKILL.md   14 skills
```

## Instalação
1. Copia **o conteúdo** desta pasta para a raiz do repo do mod (onde está o `build.gradle`).
2. Se ainda não é repo git: `git init`, e liga ao GitHub com `git remote add origin <url>`.
3. Edita o `AGENTS.md` e o `progresso.md` e preenche os campos `[ENTRE_PARÊNTESIS]`.
4. Abre o opencode na raiz do repo.

Primeira mensagem sugerida:
> Lê o AGENTS.md e o progresso.md. Faz a fase 0 (reconhecimento) do port e diz-me os maiores riscos antes de mexeres em código.

## Auto commit / auto push
O `auto-commit.js` corre quando a sessão fica parada (`session.idle`):
- Se houver alterações, faz `git add -A` e `git commit` com prefixo `wip(auto):`.
- Se existir remote `origin`, faz `git push` (desliga com `OPENCODE_AUTOPUSH=0`).
- Não faz nada se não houver alterações ou se não for repo git.

Variáveis de ambiente (opcionais):
| Variável | Efeito |
|---|---|
| `OPENCODE_AUTOPUSH=0` | não faz push |
| `OPENCODE_AUTOCOMMIT=0` | desliga o auto commit |

A skill `auto-commit` ensina o agente a fazer commits **bons** (mensagens claras,
um commit por mudança lógica). O plugin é só a rede de segurança.

## Notas importantes
- **Pastas:** versões diferentes do opencode aceitam `plugins/` ou `plugin/` e
  `skills/` ou `skill/`. Se algo não for detetado, renomeia a pasta (singular/plural).
  Confirma na documentação do opencode da tua versão.
- O plugin usa `git add -A`: confirma que o `.gitignore` está bom **antes** da primeira sessão.
- Se o repo for público, não deixes segredos em ficheiros: o auto push envia tudo o que não estiver ignorado.
- As skills são guias genéricos. Não substituem verificar a API real da versão destino.
