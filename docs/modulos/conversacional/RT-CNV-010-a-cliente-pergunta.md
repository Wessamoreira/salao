---
id: RT-CNV-010
titulo: "A cliente pergunta: horário, serviço e preço — e a recepção marca"
modulo: conversacional
fase: 4
perfil: completo
status: em-andamento
depende_de: [RT-CNV-004, RT-CNV-008, RT-AGD-001, RT-CAT-002]
permissoes: []
eventos: []
regras: [RN-CNV-027, RN-CNV-034, RN-CNV-035, RN-CNV-036]
atualizado_em: 2026-09-27
---

# RT-CNV-010 — A cliente pergunta

## 1. Objetivo

Quem escreve para o salão e não é da equipe passa a ser **respondido**: que horários há, que serviços
existem, quanto custam. Marcar continua sendo da recepção — e o agente avisa que alguém vai chamar.

## 2. Contexto de negócio

Até aqui, número desconhecido recebia um aviso fixo — *"aqui atende um assistente que trabalha só
para a equipe; ele não atende clientes"* — e silêncio depois. Foi decisão do dono em 19/09/2026, e
está registrada na pergunta 8 de `docs/13`: *"muda o modelo de identidade inteiro e abre superfície de
abuso que a Fase 4 não prevê"*.

Em 27/09/2026 o dono reabriu, e **estreitou**: a cliente conversa, mas **só consulta**. Isso desarma
as duas objeções de então em vez de aceitá-las como custo:

- **O modelo de identidade não muda.** A cliente continua não sendo ninguém no sistema. Ela não vira
  usuário, não herda permissão de conta nenhuma, e `emNomeDe` continua sendo exclusivo da equipe.
- **A superfície de abuso não abre.** Não há ferramenta de escrita no caminho dela — nem para
  simular. O que um modelo comprometido pode conseguir é, no máximo, ler o que já é público no
  balcão.

O que sobra é a metade que dá dinheiro: a pessoa pergunta às 22h se tem horário sábado, recebe
resposta, e a recepção acorda com o pedido na mão em vez de com uma pergunta não respondida.

## 3. Atores e permissões

| Quem | O quê | Permissão |
|---|---|---|
| Quem escreve e não é da equipe | pergunta horário, serviço e preço | **nenhuma** — ver §8 |

## 4. Pré-condições

- [x] Orquestração com allowlist e teto de custo (RT-CNV-004)
- [x] Aviso de IA a quem não é da equipe (RT-CNV-008) — **o texto muda aqui**
- [x] Consultar disponibilidade (RT-AGD-001) e preço com vigência (RT-CAT-002)
- [ ] Provedor de LLM contratado (pergunta 14) — sem ele nada roda ponta a ponta

## 5. Fluxo

1. Chega mensagem de um número que não é da equipe.
2. Na primeira vez, sai o aviso de IA — agora com o texto certo: é um assistente, ele consulta e
   **não marca**, e quem marca é a equipe.
3. A conversa roda com o **catálogo da cliente**: serviços, preços, horários livres. Nada mais.
4. Quando ela demonstra o que quer, o agente diz que a equipe vai chamar — e põe o recado na saída.
5. A recepção lê o recado, confere e marca pelos caminhos de sempre.

## 6. Fluxos alternativos

**A1 — Ela insiste em marcar.** O agente não tem a ferramenta; responde que quem marca é a equipe e
manda o recado. Não é uma recusa educada do modelo: é a ausência da ferramenta.

**A2 — Ela pede o que não é dela.** "Quanto a Ana faturou?", "quem tem horário marcado às 15h" — o
catálogo da cliente não tem como responder, e a tentativa entra na trilha como bloqueada.

**A3 — Número errado, spam, robô.** É o custo novo desta rotina, e é dinheiro. Ver RN-CNV-035.

**A4 — Sem provedor de LLM.** Nada muda para pior: as mensagens esperam na fila, como hoje, e o
aviso de IA (que não passa pelo modelo) continua saindo.

## 7. Regras de negócio

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| **RN-CNV-027** *(revisada)* | O aviso passa a dizer o que o agente FAZ, não só o que não faz | `AvisoDeIa` |
| **RN-CNV-034** | A cliente não é usuário efetivo: conversa sem contexto de segurança | `ProcessadorDeInteracoes` |
| **RN-CNV-035** | Desconhecido tem teto de custo próprio, menor que o do salão | `ConsumoDeIaJdbc` |
| **RN-CNV-036** | O catálogo da cliente é outro, e não tem escrita nenhuma | `CatalogoDeFerramentas` |

## 8. O que este documento decide de desenho

**A cliente não vira um usuário efetivo, nem um perfil novo.** A tentação é criar um `Perfil.CLIENTE`
com poucas permissões e reaproveitar tudo. Seria a decisão errada: todo o mecanismo de permissão do
sistema existe para dizer *o que uma conta do salão pode*, e a cliente não tem conta. Um perfil vazio
a poria dentro do mecanismo, e daí em diante toda permissão nova teria de lembrar de excluí-la — a
lista de exclusões que alguém esquece de atualizar.

Em vez disso, a conversa dela roda **sem `SecurityContext` nenhum**. Não é "sem permissão": é sem
identidade. `emNomeDe` continua recusando rodar para quem não é da equipe, e continua sendo o único
caminho até um `@PreAuthorize`.

**As ferramentas dela são OUTRAS, não as mesmas com menos permissão.** `consultar_disponibilidade`
da equipe devolve o id de cada profissional e serve para marcar em seguida;
`horarios_livres` da cliente devolve faixas de horário. Reaproveitar a ferramenta e filtrar a saída
seria a mesma tranca escrita duas vezes, e a segunda cópia é a que deixa passar um campo quando
alguém acrescenta um.

**Nenhuma escrita, nem para simular.** Desde RT-CNV-005 a escrita da equipe é oferecida ao modelo e
o que ele alcança é só a simulação — a gravação exige o "SIM + código" da pessoa. Para a cliente nem
isso: a ferramenta não existe no catálogo dela. Prompt injection tem de esbarrar em permissão, não em
prompt, e aqui esbarra em algo ainda mais duro: em ausência.

**O teto de custo do desconhecido é separado, e menor.** Hoje há um teto diário por salão. Se o
tráfego de desconhecidos consumir dele, um número em laço — ou cem números errados — deixa a equipe
sem agente no meio do expediente. Então são dois potes: o do salão, e um pote menor dentro dele para
quem não é da equipe. Estourado o pote pequeno, a cliente deixa de ser respondida e **a equipe
continua funcionando**, que é a ordem certa de degradar.

**O aviso muda de conteúdo, não de mecânica.** Continua fixo, continua sem passar pelo modelo,
continua uma vez por número pela impressão com chave (RN-CNV-029). O que muda é o que ele diz: até
aqui era uma porta fechada; agora é o que a LGPD pede de verdade — você está falando com uma IA, ela
consulta horários, ela não marca, e quem marca é gente.

## 9. Dados

Nenhuma tabela nova. O teto do desconhecido é uma coluna a mais em `consumo_de_ia` — o mesmo pote,
com uma marca de nível.

## 10. O que o dono decidiu (27/09/2026)

**O recado vai para quem pode marcar** — quem tem `agenda:write:all` e telefone ligado. Deriva de
permissão, e não de configuração: quando alguém entra ou sai da recepção, a lista se corrige
sozinha. Um número configurado seria mais uma coisa para o salão manter certa, e apontada para quem
saiu os pedidos sumiriam em silêncio — a pior falha possível para um pedido de cliente.

Com RT-IAM-010, "telefone ligado" passou a ser *qualquer um dos dois*: o recado vai para o número
que a pessoa usa.

**O desconhecido pode consumir metade do teto diário.** Estourada a metade, a cliente deixa de ser
respondida e a equipe continua funcionando. Cem números errados de madrugada não podem deixar a
recepção sem agente no dia seguinte.

## 11. Testes

| O que prova | Onde |
|---|---|
| A cliente pergunta e é respondida | `ClientePerguntaIT` |
| A conversa dela roda sem contexto de segurança nenhum | `ClientePerguntaIT` |
| O catálogo dela não tem escrita — nem para simular | `ClientePerguntaIT` |
| Pedir o financeiro ou a agenda alheia entra na trilha como bloqueado | `ClientePerguntaIT` |
| O recado vai para quem tem `agenda:write:all` e telefone | `ClientePerguntaIT` |
| Quem não tem telefone ligado não recebe recado, e isso não falha | `ClientePerguntaIT` |
| Estourada a metade, a cliente para e a EQUIPE continua | `ClientePerguntaIT` |
| O aviso de IA diz o que o agente faz, uma vez por número | `AvisoDeIaIT` |
| Sem provedor, a fila espera e o aviso continua saindo | `AvisoDeIaIT` |

## 12. O que foi construído

*(a preencher ao fim da rotina)*
