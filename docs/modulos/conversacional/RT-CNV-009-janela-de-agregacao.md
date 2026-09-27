---
id: RT-CNV-009
titulo: "Janela de agregação: esperar a pessoa terminar de falar"
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-001, RT-CNV-003, RT-CNV-004]
permissoes: []
eventos: []
regras: [RN-CNV-030, RN-CNV-031, RN-CNV-032]
atualizado_em: 2026-09-27
---

# RT-CNV-009 — Esperar a pessoa terminar de falar

## 1. Objetivo

Uma volta do modelo por **rajada**, e não por mensagem. Quem manda "oi", pensa, manda o resto e
ainda grava um áudio fez **um** pedido — e hoje o agente responde três vezes, cada uma sem saber
do que veio depois.

## 2. Contexto de negócio

É assim que as pessoas usam WhatsApp:

> **oi**
> *(um minuto)*
> **queria marcar com a Ana**
> *(áudio de 12 segundos)* "pode ser quinta de tarde, depois das duas"

Hoje isso é três execuções. A primeira responde "oi, como posso ajudar?" — inútil e paga. A
segunda pergunta o horário que a terceira já traz. E a terceira chega sem saber que as outras
existiram, porque o histórico só enxerga trocas **concluídas**.

O custo é o menos importante. O pior é a resposta: o agente parece que não estava prestando
atenção, que é exatamente a sensação que faz alguém desistir de falar com um robô.

**O áudio é o caso difícil.** Ele chega, é baixado e transcrito — e isso leva segundos ou dezenas
deles. Uma janela que conte só o silêncio de *texto* dispara antes da transcrição chegar, e o
áudio vira uma quarta resposta solta.

## 3. Atores e permissões

Nenhuma nova. A janela é anterior à identidade: ela decide **quando** processar, e quem pode o
quê continua sendo RT-CNV-002 e RN-CNV-010.

## 4. Pré-condições

- [x] Fila de execuções por mensagem (RT-CNV-004)
- [x] Transcrição de áudio com estado próprio (RT-CNV-003)
- [x] Índice `(estabelecimento_id, de_telefone, recebida_em desc)` em `mensagem_recebida` (V50)

## 5. Fluxo

1. A mensagem chega e vira uma execução **pendente**, como hoje. Uma linha por mensagem —
   idempotência e trilha continuam de pé.
2. O processador só **reclama** as pendentes de um telefone quando aquele telefone está em
   silêncio há mais que a janela **e** não tem áudio em transcrição.
3. Reclamadas, todas as pendentes daquele telefone viram **uma** entrada, na ordem em que
   chegaram, e **uma** volta do modelo.
4. A última responde; as outras ficam `JUNTADA`, apontando para ela.

## 6. Fluxos alternativos

**A1 — Transcrição travada.** Um áudio que não transcreve não pode segurar a rajada para sempre.
Passado o **teto de espera**, a janela segue sem ele; quando a transcrição terminar, ela vira uma
execução nova e o agente responde de novo — que é o comportamento de hoje, e é melhor do que a
conversa parar.

**A2 — A pessoa não para de escrever.** Cada mensagem nova reinicia o silêncio. O teto de espera
também vale aqui: passado ele, o agente responde com o que tem. Quem escreve por dez minutos sem
parar recebe resposta no meio, e é o certo — o contrário é o agente mudo.

**A3 — Botão.** Toque em botão de aceite **não espera**: ele não passa pelo modelo, não custa nada
e a pessoa está olhando a tela esperando o efeito (RT-CNV-006).

**A4 — Rajada que mistura botão e texto.** O botão sai na frente, sozinho; o texto agrega
normalmente. São dois atos diferentes.

## 7. Regras de negócio

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| **RN-CNV-030** | Uma volta do modelo por rajada, não por mensagem (20 no máximo) | `ProcessadorDeInteracoes.RESERVAR` |
| **RN-CNV-031** | O silêncio conta mensagem recebida, não execução pronta | idem, via `mensagem_recebida` |
| **RN-CNV-032** | Nenhuma espera é infinita: o teto vence a janela | idem, via `proxima_tentativa_em` |

## 8. O que este documento decide de desenho

**A janela mora na consulta que reclama, e em nenhum outro lugar.** É uma decisão de *quando*
processar, e a fila já tem a coluna que significa isso: `proxima_tentativa_em`. Um agendador
separado, uma fila em memória ou um `sleep` no consumidor seriam um segundo lugar onde o "quando"
mora — e o segundo lugar é o que diverge quando duas instâncias rodam.

**Uma linha por mensagem, sempre.** A alternativa tentadora é juntar no momento em que a mensagem
chega: achar a pendente daquele telefone e concatenar o texto nela. É menos código e mente duas
vezes — a execução passaria a dizer que é da mensagem X carregando o texto de X, Y e Z, e a
unicidade por `mensagem_id`, que é o que torna o reenvio da Meta inofensivo, iria junto.

**O silêncio se mede em `mensagem_recebida`, não em `execucao_do_agente`.** É a única forma de a
janela enxergar um áudio que ainda está sendo transcrito: ele já é uma mensagem recebida e ainda
não é uma execução. Medir pela fila de execuções faria o texto ser respondido antes de o áudio
existir — e o áudio é o caso que esta rotina existe para resolver.

**A rajada tem tamanho máximo.** Vinte mensagens. Ninguém manda vinte frases em quinze segundos
querendo dizer uma coisa só — mas uma enxurrada (engano, script, número em laço) viraria um prompt
gigante que o limite por telefone contaria como UMA conversa, e o teto de custo do dia iria embora
numa chamada. O que passa disso vira a rajada seguinte, na ordem, respondida logo depois.

**A espera tem teto, e o teto é do salão.** Quinze segundos de silêncio servem para quem digita;
trinta não incomodam ninguém. Mas nada pode esperar para sempre: transcrição que trava, pessoa que
não para de escrever, provedor fora do ar. `proxima_tentativa_em + teto` é a hora em que o agente
responde com o que tem.

**`JUNTADA` é um estado, não um apagamento.** As mensagens absorvidas continuam na fila, com
`respondida_por` apontando para a que respondeu. A trilha de RT-CNV-007 precisa responder "o que o
agente fez com esta mensagem?" — e "foi respondida junto com a seguinte" é uma resposta; sumir não
é.

## 9. Dados

`V68` acrescenta a `execucao_do_agente`:

- o estado `JUNTADA` no `check` de `status`;
- `respondida_por uuid references execucao_do_agente(id)` — quem respondeu por esta;
- índice `(estabelecimento_id, de_telefone, proxima_tentativa_em)` parcial em `PENDENTE`, que é
  como a janela agrupa.

E a `mensagem_recebida`, `sequencia` — a ordem de chegada como fato. Nenhuma coluna de hoje
respondia isso: `recebida_em` empata dentro de um lote da Meta, `enviada_em` tem resolução de
segundo, e a ordem da fila **inverte o áudio**, que só vira execução quando a transcrição termina.

A reserva atravessa salões, então lê `mensagem_recebida` pela role de plataforma — com grant **por
coluna**: `texto` e `midia_id` ficam de fora. Saber que às 14h02 chegou algo daquele número é o que
a janela precisa; ler o pedido da cliente, não.

Nenhuma tabela nova. A rotina é sobre **quando**, não sobre o quê.

## 10. Configuração

| Chave | Padrão | O que é |
|---|---|---|
| `app.agente.janela-de-agregacao` | `PT15S` | silêncio antes de responder |
| `app.agente.teto-de-espera` | `PT2M` | o quanto a rajada pode segurar o agente |

Padrões, e não decisão do dono: quinze segundos é o tempo de digitar a segunda frase, e dois
minutos é mais do que qualquer transcrição honesta demora. Viram pergunta ao dono no dia em que
alguém reclamar — e aí a resposta já tem onde morar.

## 11. Testes

| O que prova | Onde |
|---|---|
| Três mensagens em rajada viram UMA volta do modelo | `JanelaDeAgregacaoIT` |
| A entrada chega na ordem em que as mensagens chegaram | `JanelaDeAgregacaoIT` |
| As absorvidas ficam `JUNTADA`, apontando para quem respondeu | `JanelaDeAgregacaoIT` |
| Mensagem sozinha não espera mais que a janela | `JanelaDeAgregacaoIT` |
| Texto espera o áudio que ainda está sendo transcrito | `JanelaDeAgregacaoIT` |
| Transcrição travada não segura a rajada além do teto | `JanelaDeAgregacaoIT` |
| Quem não para de escrever recebe resposta no teto | `JanelaDeAgregacaoIT` |
| Botão não espera janela nenhuma | `JanelaDeAgregacaoIT` |
| Rajadas de telefones diferentes não se misturam | `JanelaDeAgregacaoIT` |
| Enxurrada é cortada em 20, e o resto vira a rajada seguinte | `JanelaDeAgregacaoIT` |
| Uma ação na trilha por rajada, com o pedido inteiro | `JanelaDeAgregacaoIT` |
| O limite por telefone passa a contar rajadas | `AgenteIT.limite_por_telefone` |

## 12. O que foi construído

**Banco — `V68`.** `mensagem_recebida.sequencia` (a ordem de chegada como fato); política e grant
**por coluna** dessa tabela para a role de plataforma; em `execucao_do_agente`, o estado `JUNTADA`,
`respondida_por` com as duas constraints que o amarram nos dois sentidos, e os índices da rajada.

**`ProcessadorDeInteracoes`.** A consulta `RESERVAR` virou quatro CTEs — `pronta`, `rajada`,
`madura`, `escolhida` — e é o único lugar onde a janela existe. O agrupamento em rajadas é em
memória: o banco decide **quais** linhas saem, o domínio decide **quem responde** e **o que o modelo
lê**.

**`ConcluirExecucao.Alvo`.** Todo desfecho — concluída, recusada, falhou — passa a ser da rajada
inteira, no mesmo commit. `ExecucoesJdbc.juntar` marca as absorvidas; `adiar` solta as duas pontas,
para a rajada se reformar igual na volta seguinte.

**Trilha.** `AcaoIaJdbc.REGISTRAR` remonta o pedido inteiro com `string_agg` e marca
`veio_de_audio` se **qualquer** mensagem da rajada foi áudio. Uma ação por rajada, que é o que
aconteceu.

**Nada no front.** A tela de revisão (RT-CNV-007) já renderia: `.agt-fala dd` é `pre-wrap`, então o
detalhe mostra as mensagens em linhas; a linha da lista é `nowrap`, e as quebras viram espaço num
resumo de uma linha. Foi verificado, não presumido.

**Efeito colateral registrado:** o limite por telefone (RN-CNV-013) passa a contar **rajadas**. É
mais certo — o limite protege o teto de custo, e o custo é por volta do modelo —, e é por isso que
a rajada ganhou teto de tamanho: sem ele, uma enxurrada seria uma conversa só.

## 13. Pendências

- **O silêncio é global ao número, não por salão.** Duas unidades do mesmo grupo com números
  diferentes não se atrapalham (a consulta casa por `estabelecimento_id` também), mas a mesma pessoa
  escrevendo para dois salões espera a janela dos dois. É o certo hoje e vira pergunta se aparecer.
- **A janela e o teto são padrões, não escolha do dono.** Quinze segundos e dois minutos servem
  para quem digita; viram configuração por salão no dia em que alguém reclamar. O lugar já existe.
- **Métrica sem painel.** `conversacional.rajada` registra o tamanho de cada rajada — é o número que
  responde "a janela está valendo a pena?". Ninguém olha para ele ainda.
