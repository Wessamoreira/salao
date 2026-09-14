---
id: RT-CNV-004
titulo: "Orquestração: allowlist de tools por perfil, teto de custo, rate limit"
modulo: conversacional
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-002, RT-CNV-003, RT-INF-006]
permissoes: []
eventos: [MensagemDeClienteRecebida]
regras: [RN-CNV-010, RN-CNV-011, RN-CNV-012, RN-CNV-013]
atualizado_em: 2026-09-13
---

# RT-CNV-004 — Orquestração do agente

## 1. Objetivo

Quem é da equipe escreve (ou fala) pelo WhatsApp; o agente responde, consultando o sistema — e não
consegue fazer nada além do que essa pessoa já pode, nem gastar além do teto do salão.

## 2. A pergunta certa sobre prompt injection

"Como impedir o modelo de ser enganado" não tem resposta: mensagem de cliente é entrada não
confiável, e um modelo pode cair. A pergunta que tem resposta é **o que acontece quando ele cair** —
e a resposta desta rotina é: nada além do que o usuário efetivo já pode pela tela.

Por isso a suíte R-10 não testa frases de ataque. Ela usa um modelo **roteirizado para obedecer**:
ele pede ler o financeiro, cancelar tudo, listar usuários e apagar o banco. O que se prova é que,
mesmo com o modelo inteiramente comprometido, nenhum desses pedidos executa.

## 3. Três trancas, nenhuma no prompt — RN-CNV-010

| Tranca | Onde | Pega |
|---|---|---|
| o modelo só **vê** a lista permitida | `CatalogoDeFerramentas.permitidasPara` | a oferta de ferramenta indevida |
| pedido fora da lista é **recusado** | `Orquestrador.avaliar` | ferramenta inexistente, de escrita, ou não permitida |
| a execução roda **em nome do** usuário | `emNomeDe` → `@PreAuthorize` | a ferramenta que declarou permissão errada |

A terceira não é redundante. `listar_usuarios`, no teste, declara `cliente:read` — que a recepção
tem — e chama um caso de uso que exige `usuario:manage`. A lista a oferece; o `@PreAuthorize`
recusa. Sem `emNomeDe`, a mutação mostra: a chamada sai sem contexto de segurança nenhum.

**Escrita nunca é executada aqui**, nem com permissão. Marcar, remarcar e cancelar passam por
simular → confirmação humana explícita → confirmar (RT-CNV-005/006).

> **Atualizado por RT-CNV-005.** A escrita deixou de ser uma `Natureza` recusada num `if`
> (`ESCRITA_EXIGE_CONFIRMACAO`): o contrato foi selado em leitura e escrita, pedir uma escrita produz
> uma `PROPOSTA`, e só a mensagem da pessoa com o código grava (RN-CNV-014). Os testes e mutações
> abaixo são os desta rotina, com os nomes da época.

E nenhuma frase de segurança no prompt. "Não execute ações perigosas" é uma instrução que a
mensagem seguinte pode revogar.

## 4. Ferramentas moram no módulo dono — RN-CNV-013

A segunda tranca só existe se a ferramenta chamar o **caso de uso** com o `@PreAuthorize`. Mas a
fronteira proíbe `conversacional` de chamar caso de uso interno de outro módulo; chamando a API
pública, a tranca some. E pôr o contrato em `conversacional` faria agenda, cliente e financeiro
dependerem do agente.

O contrato `Ferramenta` foi para `shared.agente`, e cada módulo contribui as suas como beans. A
primeira é `listar_servicos`, do `catalogo`, chamando `ListarServicosUseCase`. As de agenda são
RT-CNV-005.

## 5. Encerrar sem gastar — RN-CNV-012

Na ordem, e cada passo encerra sem chamar o modelo:

1. **identidade** (RT-CNV-002) — quem não é usuário efetivo é recusado com o motivo da identidade;
2. **limite por telefone** — trinta conversas por hora por número, por instância;
3. **teto de custo** — reservado antes de cada chamada.

Recusa não responde nada a ninguém: o motivo é diagnóstico.

## 6. Teto de verdade — RN-CNV-011

O custo só é conhecido quando a chamada volta. Conferir antes e gravar depois deixaria chamadas
concorrentes passarem juntas pelo mesmo saldo. Então:

```
reservar(pior caso)   → insert ... on conflict do update ... where cabe   (um comando)
chamar o modelo       → fora de transação
liquidar(real)        → troca a reserva pelo custo real
falhou?               → liquidar(0): a reserva não fica presa
```

O dia é o dia civil no **fuso do salão**. E sem preço configurado o agente não roda: sem preço não
há custo, e sem custo o teto é ficção. Um preço padrão no código seria uma tabela inventada.

## 7. Uma fila, como o áudio

Mesmo motivo de RN-CNV-006: chamadas ao modelo levam segundos e encadeiam. O listener enfileira; o
`ProcessadorDeInteracoes` reserva a linha de forma atômica (RN-CNV-008) e conduz fora de transação.

Texto entra pelo `EnfileiradorDeInteracoes`. Áudio entra quando a transcrição termina — e
`ConcluirTranscricao` faz as duas coisas no **mesmo commit**: separadas, uma queda entre elas
deixaria a transcrição pronta e ninguém na fila para responder.

`entrada` é congelada na execução, e o histórico da conversa sai dali: as seis últimas trocas das
últimas 24 horas do mesmo telefone.

## 8. O adaptador

`ClaudeMessagesApi` implementa `ModeloDeLinguagem` com o `HttpClient` do JDK. O endpoint é fixo no
bean de produção, e não propriedade — uma URL de provedor configurável seria a forma de mandar a
chave de API para qualquer lugar. Redirect não é seguido. O erro guardado em `ultimo_erro` é só o
status: o corpo de erro às vezes ecoa o pedido, e o pedido tem a mensagem da pessoa.

O modelo padrão é `claude-opus-5` e é configurável. Nada roda sem chave e preço.

## 9. Dois defeitos meus, pegos antes de compilar

- **Consulta dupla com janela de corrida.** O processador chamava `identificarPorTelefone` duas
  vezes: uma para decidir, outra para ler. Desativado entre as duas, a segunda viria
  `NaoIdentificado` e o cast estouraria. Virou uma chamada só.
- **Código sem função** no `toString` do adaptador. Apagado.

### E um que só o verify completo mostrou

Os testes da rotina passaram sozinhos; o `verify` completo falhou no `WebhookDoWhatsAppIT` — uma
classe que esta rotina não tocou — com `deadlock detected` na limpeza entre testes.

O log mostrou o ciclo: o `EnfileiradorDeInteracoes`, assíncrono, ainda inseria em
`execucao_do_agente` a conversa disparada pelo teste anterior, esperando lock na tabela referenciada
pela FK; o `TRUNCATE ... CASCADE` do teste seguinte esperava lock exclusivo em `execucao_do_agente`.
O Postgres abortou um dos dois.

A causa é desta rotina: o enfileirador passou a reagir a TODA mensagem de texto, e aquela classe
entrega texto em quase todo teste. Não é defeito de produção — não há `TRUNCATE` em produção, e um
listener abortado deixa a publicação pendente para o `ReenviadorDeEventos`. Na suíte, a limpeza
passou a tentar de novo em `40P01`, e só nele.

## 10. Testes obrigatórios

- [x] `OrquestradorTest` — 11: responde com custo reservado e liquidado · ferramenta permitida e o
  resultado de volta ao modelo · **o modelo só vê a lista** · histórico como mensagens · **modelo
  comprometido: inexistente, fora da lista, escrita e permissão mentida recusados, e só a permitida
  chega ao executor** · escrita recusada com permissão · **laço sem fim para no limite** · erro de
  domínio volta como erro, **falha inesperada não vaza detalhe** · teto antes da primeira chamada ·
  teto no meio · **falha do modelo solta a reserva**.
- [x] `AgenteIT` — 11, com identidade, limite, teto e `@PreAuthorize` reais: **a recepção conversa e
  a ferramenta roda em nome dela** · histórico · **áudio transcrito vira conversa no mesmo commit**
  · **modelo comprometido de ponta a ponta** · desconhecido não chama o modelo · dono não conversa ·
  **limite por telefone** · **teto do dia** · sem modelo espera · falha transitória adia e solta a
  reserva · a aplicação não apaga.
- [x] `ClaudeMessagesApiTest` — 7, contra servidor HTTP do JDK: chave, versão e ferramentas na ida,
  texto, pedido e uso na volta · resultado de ferramenta no formato da API · sem ferramentas não
  manda `tools` · 429, 529 e 401 transitórios, 400 permanente · **corpo do erro não vaza** ·
  redirect não seguido · `toString` sem a chave.
- [x] `CatalogoDeFerramentasTest` — 5: todas as permissões · **escrita nunca** · **sem declaração
  nunca** · nome repetido e nome inválido derrubam a subida.

### Mutações verificadas

Tudo passou na primeira execução. Duas rodadas de mutação, cada uma numa execução só.

**Rodada A** — oito linhas quebradas juntas; ficaram vermelhos exatamente os doze testes esperados,
e nenhum outro:

| Linha quebrada de propósito | Testes que ficaram vermelhos |
|---|---|
| a recusa `FORA_DA_LISTA` desligada | `OrquestradorTest.modelo_comprometido`, `AgenteIT.modelo_comprometido` |
| o catálogo oferecendo escrita | `CatalogoDeFerramentasTest.escrita_nunca`, `OrquestradorTest.so_ve_a_lista`, `AgenteIT.recepcao_conversa` |
| a reserva sem a condição de caber no teto | `AgenteIT.teto_de_custo` |
| a falha do modelo sem soltar a reserva | `OrquestradorTest.falha_solta_reserva`, `AgenteIT.falha_transitoria` |
| o limite por telefone desligado | `AgenteIT.limite_por_telefone` |
| o corpo do erro na mensagem | `ClaudeMessagesApiTest.corpo_de_erro_nao_vaza` |
| o detalhe da exceção devolvido ao modelo | `OrquestradorTest.erros_da_ferramenta` |
| o laço sem limite de iterações | `OrquestradorTest.laco_infinito` |

**Rodada B** — separada porque os testes se sobrepõem aos da A: a ferramenta executada **sem**
`emNomeDe`. Ficaram vermelhos `AgenteIT.modelo_comprometido` e `AgenteIT.recepcao_conversa`. Sem o
contexto do usuário efetivo não há autoridade nenhuma — até a ferramenta legítima falha, e a
mentirosa deixa de ser recusada por permissão para falhar por falta de contexto. É a prova de que a
terceira tranca depende de `emNomeDe`, e não de a ferramenta ser bem-comportada.

## 11. Pendências

- [ ] **A resposta não é entregue.** Fica em `execucao_do_agente.resposta`. Não há mensageiro real
      (RT-NOT-001 só registra), e a fila de notificação só fala com CLIENTE (o sistema não sabe
      falar com a própria equipe). Entregar a resposta é a próxima coisa que falta para o agente
      existir de fato.
- [ ] **Escolher o provedor de LLM e informar o preço** (pergunta 14). Sem chave e preço, as
      conversas esperam.
- [ ] **O dado vai para o provedor.** Mensagem, nome de quem escreve e resultado de ferramenta são
      enviados ao LLM: é operação de tratamento por terceiro, e pede contrato de processamento de
      dados (LGPD) antes de ligar em produção.
- [ ] **`acao_ia` e o painel de revisão** são RT-CNV-007. O rastro operacional desta rotina —
      ferramentas, desfechos, tokens e custo — está em `execucao_do_agente`, sem a retenção e a
      anonimização que `04-modelo` prevê para `acao_ia`.
- [ ] **Argumentos de ferramenta não são validados contra o esquema.** Cada ferramenta valida os
      seus, como uma rota HTTP faria — desde RT-CNV-005 com `ArgumentosDaFerramenta`; um validador de
      JSON Schema central ainda não existe.
- [ ] **O teto é um valor só para todos os salões.** O controle é por salão; o número, não. Um
      campo na configuração do salão é o próximo passo quando alguém pedir.
- [ ] **O limite por telefone é por instância.** Com N instâncias, é N vezes maior. O teto, no
      banco, é global.
- [ ] **Retentativa depois de queda repete o custo.** Uma conversa interrompida no meio volta do
      começo e chama o modelo de novo. As ferramentas são de leitura, então não há efeito repetido
      — só custo.
- [ ] **Remetente desconhecido não recebe resposta.** É a regra da v1 (pergunta 8); o aviso de IA à
      cliente é RT-CNV-008.
