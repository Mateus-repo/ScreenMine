---
name: api-verification
description: Confirmar nomes reais de classes, métodos e assinaturas da versão destino antes de os usar. Usar sempre que um nome é duvidoso, em renames e quando o compilador dá erros de símbolo.
---
# Verificação de API

**Regra:** pesquisa na web e memória ajudam a procurar, mas **o jar real decide**.

## Como verificar
- Gera/abre as fontes: `./gradlew genSources` (ou o equivalente do Loom da tua versão) e pesquisa com `grep -r "NomeDaClasse" <sources>`.
- `javap -classpath <jar> -public <pacote.Classe>` para ver métodos e assinaturas.
- Confirma o **pacote** (classes mudam de pacote entre versões) e o **tipo de retorno**.
- Usa o autocompletar/erros do compilador como confirmação, não como adivinha.

## Armadilhas
- **Mesmo nome, outra semântica** (ex.: um getter que antes devolvia direção e agora devolve ângulos). Rename automático **cria bugs silenciosos**: trata à mão.
- Classe movida de pacote ou renomeada.
- Método passou de instância para estático (ou ao contrário).
- Parâmetros ganharam/perderam argumentos; records no lugar de vários parâmetros.

## Processo para rename em massa
1. Lista todos os candidatos (`grep`) e a ocorrência de cada um.
2. Verifica cada um contra o jar.
3. Aplica só os de equivalência exata e confirmada.
4. Deixa os ambíguos para revisão manual com um `// TODO(port)`.

## Registo
Cada descoberta útil vai para a tabela "Descobertas" do `progresso.md` (antes → agora).
