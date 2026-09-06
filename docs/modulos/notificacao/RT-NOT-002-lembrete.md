---
id: RT-NOT-002
titulo: Lembrete de agendamento 24h antes (sem IA)
modulo: notificacao
fase: 1
perfil: completo
status: implementado
depende_de: [RT-NOT-001, RT-AGD-002]
permissoes: []
eventos: []
regras: [RN-NOT-001, RN-NOT-003, RN-NOT-004]
atualizado_em: 2026-09-05
---

# RT-NOT-002 — Lembrete de 24h

> O plano chama esta de **maior retorno de negócio do sistema inteiro**: lembrete reduz no-show de
> forma drástica, e cada no-show evitado é um horário que o salão vende. Sem agente de IA nenhum.

## 1. A janela é larga de propósito

De 12 a 26 horas à frente, e não de 24 a 25.

Uma faixa estreita deixaria **sem lembrete todas as clientes daquela hora** se a execução falhasse —
reinício, deploy, uma exceção qualquer — e o silêncio não apareceria em lugar nenhum. Ninguém abre
um painel para conferir mensagens que não foram mandadas.

Com a faixa larga, a execução seguinte alcança as mesmas clientes. A duplicata é impedida por
RN-NOT-003 — índice único sobre a referência — e **não** por acertar o horário: precisão de
agendamento de job é a garantia mais frágil que existe, e apoiar a correção nela seria trocar uma
garantia do banco por uma do relógio.

É a mesma escolha do reenviador de eventos: repetir é barato, perder é caro.

## 2. Quem acabou de marcar não é lembrada

A cliente que combinou o horário no balcão há dez minutos não precisa de uma mensagem dizendo o que
ela acabou de combinar.

Duas horas de idade mínima resolvem, e a janela larga garante que ela seja alcançada na execução
seguinte — a regra adia o lembrete, não o cancela.

## 3. "amanhã às 14h", não "18/09 às 14:00"

A mensagem chega no dia anterior. **"Amanhã" é como a pessoa pensa no horário.**

Escrever a data por extenso obriga quem lê a converter, e a conversão é onde ela erra o dia — que é
exatamente o erro que o lembrete existe para evitar.

## 4. Um salão com problema não cala os outros

Tenant a tenant, com o erro contido, como o reenviador de eventos. Um salão com dado inconsistente
não pode fazer o vizinho perder os lembretes do dia.

E cliente sem telefone não é erro do sistema: é cadastro incompleto, o caso mais comum de todos.
Vai para `debug` e o lote segue.

## 5. A revisão de fronteira que a rotina exigiu

O módulo `agenda` declara teto de **três** dependências, e já estava nele. Esta rotina acrescenta
`notificacao` — a quarta.

A regra manda revisar a fronteira, e a revisão produziu uma distinção que faltava:

| | O que é | Conta no teto? |
|---|---|---|
| **Fonte** | Algo de que a agenda depende *para decidir* | Sim — mais de três significa que a decisão não é dela |
| **Destino** | Algo a quem ela *conta o que decidiu* | Não — conta à parte |

`notificacao` é destino: não participa de nenhuma regra da agenda, não pode recusar nada, e
trocá-lo por outro não muda uma linha de lógica — o lembrete continua sendo devido no mesmo
instante, mande quem mandar.

O sentido da seta é o que sustenta a distinção: `notificacao` nunca chama `agenda`. **Se um dia
chamar, virou fonte e conta no teto.** Está escrito no `_modulo.md` da agenda.

## 6. Onde o job mora, e por quê

Em `agenda`, não em `notificacao`. O `_modulo.md` de notificacao já dizia: *"`agenda` decide que
existe um lembrete a mandar; este módulo só sabe mandar"*.

Quando lembrar é regra de agenda. Como mandar é de notificacao.

## 7. Testes

- [x] `LembradorDeAgendamentosIT` — 9: **o horário de amanhã vira lembrete** · a hora se lê sem
      converter · **rodar duas vezes não manda duas mensagens** · **quem acabou de marcar não é
      lembrada** · daqui a uma semana ainda não é · daqui a duas horas já passou · **cancelado não
      é lembrado** · cliente sem telefone não quebra o lote · o laço atravessa mais de um salão

## 8. O teste dependia da hora do dia

Descoberto em RT-ATD-001, quando o build quebrou às nove da noite sem que nada tivesse mudado:
`perto_demais_nao_lembra` marcava "daqui a duas horas" contra uma jornada que ia até as 23h. Rodando
de manhã, o horário caía dentro; rodando à noite, caía fora — e o erro que aparecia era
*"o trecho está fora da jornada"*, que não tem nada a ver com o que o teste afirma.

Cenário montado com horário **relativo ao agora** herda a hora em que o build roda. A correção é a
jornada cobrir o dia inteiro: o que este teste mede é a janela do lembrete, não o expediente.

Vale para qualquer teste que use `daquiA(...)` — e é o tipo de defeito que três execuções verdes
seguidas não pegam, porque as três rodam no mesmo horário.

## 9. Pendências

- [ ] **Nada sai de fato.** O mensageiro de hoje só registra (RT-NOT-001): o lembrete é enfileirado
      corretamente e nunca chega à cliente. É a pendência que separa "a rotina funciona" de "o
      salão ganha dinheiro com ela"
- [ ] **A janela não é configurável.** Salão que atende de manhã pode querer avisar na véspera à
      noite; um que atende à noite, de manhã. Hoje são 12–26 horas para todo mundo
- [ ] **Não há lembrete de confirmação.** "Responda SIM para confirmar" é o que transforma o
      lembrete em confirmação e alimenta RT-AGD-004 — depende de receber mensagem, não só de mandar
- [ ] **Sem métrica de efeito.** A pergunta que justifica a rotina — "o no-show caiu?" — não tem
      número. Precisa cruzar `NoShowRegistrado` com a existência do lembrete
- [ ] **Segundo lembrete não existe.** Alguns salões mandam um na véspera e outro duas horas antes;
      a referência única impediria o segundo hoje, porque ela é por agendamento e não por
      *momento* do lembrete
