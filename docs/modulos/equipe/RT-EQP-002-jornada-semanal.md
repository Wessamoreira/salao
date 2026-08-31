---
id: RT-EQP-002
titulo: Jornada semanal por profissional
modulo: equipe
fase: 1
perfil: completo
status: implementado
depende_de: [RT-EQP-001]
permissoes: [usuario:manage]
eventos: []
regras: [RN-EQP-006, RN-EQP-007, RN-EQP-008]
atualizado_em: 2026-08-30
---

# RT-EQP-002 — Jornada semanal

## 1. Objetivo

Registrar em que dias e horários cada profissional atende. É o **primeiro insumo** da consulta de
disponibilidade (RT-AGD-001), que é esta jornada menos os bloqueios e menos os horários ocupados.

## 2. A decisão que define a rotina: o almoço não é um campo

O modelo que quase todo sistema de salão adota é `inicio, fim, almoco_inicio, almoco_fim` na linha
do dia. Ele funciona até a primeira profissional que:

- faz três blocos — manhã, tarde depois de um curso, noite;
- tem **dois** intervalos;
- trabalha só de manhã às quartas.

Aí alguém acrescenta `almoco2_inicio`, e o modelo vira folclore.

**Almoço é a ausência de um intervalo de trabalho** (RN-EQP-006). Uma linha por intervalo, e
"9h-13h, 14h-19h" passa a ser o caso normal em vez de uma exceção com nome próprio. Três blocos
cabem sem nenhum campo novo.

## 3. Sobreposição impossível, garantida pelo banco

Primeiro uso de `EXCLUDE` no projeto — o mecanismo que o inviolável nº 8 exige para a agenda.

Duas instâncias salvando a jornada ao mesmo tempo passariam por **qualquer** verificação em Java:
as duas leem, nenhuma vê conflito, as duas gravam. O `EXCLUDE` não tem esse buraco (RN-EQP-007).

Precisou de duas coisas que o Postgres não traz prontas:

- `btree_gist`, para combinar `=` de uuid e smallint com `&&` de faixa no mesmo índice;
- um tipo `faixa_de_hora`, porque existe `int4range` e `tsrange`, mas não um para `time`. A
  alternativa seria guardar minutos desde a meia-noite — números que ninguém lê num dump nem numa
  investigação.

**A verificação em Java existe também, e não é a garantia: é a mensagem.** Um erro de constraint
diz que algo colidiu; a tela precisa saber *quais* horários, para destacá-los.

## 4. Vazio significa "não atende"

Profissional sem jornada devolve jornada vazia, e vazia **não** é "atende sempre" (RN-EQP-008).

O padrão precisa ser o que não marca horário para alguém que não trabalha. O contrário — assumir
disponibilidade total na ausência de dado — encheria a agenda de horários que ninguém vai cumprir,
e o erro só apareceria com a cliente na porta.

## 5. A semana inteira, sempre

`PUT` substitui todos os intervalos. Aplicar diferenças — este saiu, aquele entrou — é código que
existe para economizar dez escritas por semana e que erra na primeira jornada com dois intervalos
no mesmo dia.

Mesma escolha de RT-CLI-007, e pelo mesmo motivo: quem edita manda o formulário inteiro.

O `delete` e os `insert` acontecem na mesma transação, então não existe instante em que o
profissional apareça sem jornada.

## 6. ISO em toda fronteira

`1` = segunda … `7` = domingo. É o mesmo esquema do `java.time.DayOfWeek` e do `extract(isodow)`
do Postgres.

Escolher outro — domingo=0, como fazem várias APIs — obrigaria a converter em cada fronteira, e é
exatamente aí que erro de um dia aparece: silencioso, e sempre no fim de semana.

## 7. A quarta vez da mesma família de defeito

O controller construía `IntervaloDeTrabalho` diretamente. O construtor recusa intervalo invertido
com `IllegalArgumentException` — correto para o domínio, **desastroso na borda**: sem tradução, ela
cai no tratador genérico e vira 500, dizendo "algo quebrou aqui dentro" sobre um horário que a
pessoa digitou trocado.

É a quarta ocorrência do mesmo padrão neste projeto: rota inexistente, parâmetro malformado,
permissão negada, e agora construção de domínio na borda. A regra já é conhecida — **exceção que a
borda pode provocar precisa de tradutor**.

Corrigido com `PeriodoBruto`: o controller passa horários crus e o caso de uso constrói o domínio,
traduzindo a falha. Não é cerimônia de camada — é o único lugar onde a tradução cabe.

## 8. O que ficou de fora, de propósito

**Feriado, férias, curso, atestado.** São exceções e vivem em RT-EQP-003. Misturá-las aqui faria a
jornada crescer com datas e deixaria de responder à pergunta simples que o salão faz — *"que dias
você trabalha?"*.

**Vigência.** A jornada não é versionada por data: alterá-la muda o que vale de agora em diante. A
consequência está declarada na pendência — *"quem estava escalado em março?"* não é respondível.
Versionar custaria uma tabela de histórico para uma pergunta que ninguém fez ainda.

## 9. Testes

- [x] `JornadaSemanalTest` — 8: **o almoço é ausência de intervalo** · três blocos cabem · dia sem
      jornada não atende · **o fim é exclusivo** (às 19h já foi embora) · soma dos minutos ·
      intervalo invertido recusado · encostar não é sobrepor · a jornada devolvida é imutável
- [x] `JornadaIT` — 13: grava semana com almoço · vazia significa não atende · redefinir substitui
      a semana · limpar · sobreposição recusada com os horários na mensagem · encostados aceitos ·
      **o banco impede sobreposição** · dias diferentes não colidem · profissionais diferentes não
      colidem · **invertido é 422, não 500** · profissional inexistente · isolamento entre salões ·
      sem permissão é recusado

## 10. Pendências

- [ ] **Não há tela.** Definir jornada por API é inviável para o dono do salão. Precisa vir antes
      de a agenda existir, senão a disponibilidade nasce sem insumo
- [ ] **"Quem estava escalado em março?" não é respondível** — a jornada não guarda histórico.
      Aceitável hoje; revisitar se aparecer disputa sobre escala
- [ ] **A jornada não é comparada com o horário do salão**, que aliás ainda não existe como dado.
      Hoje dá para cadastrar alguém atendendo às 4h da manhã. Entra junto de RT-CFG-* quando o
      horário de funcionamento for configurável
- [ ] **Nada impede jornada de profissional inativo.** Não causa dano (ele não aparece em
      `ativos()`), mas é dado que ninguém vai olhar
- [ ] Intervalo que atravessa a meia-noite é recusado por `check`. Salão não abre às 22h para
      fechar às 2h — se algum abrir, isto precisa de desenho próprio, não de afrouxar a restrição
