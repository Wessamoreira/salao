---
id: RT-EST-008
titulo: Alertas de estoque mínimo e de lote vencendo
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-003]
permissoes: [estoque:read, estoque:write]
eventos: []
regras: [RN-EST-024, RN-EST-025]
atualizado_em: 2026-09-12
---

# RT-EST-008 — Alertas de estoque

## 1. Objetivo

Dizer o que está acabando e o que vai vencer, antes de acabar e antes de vencer.

## 2. A lacuna que esta rotina revelou: o sistema não sabe falar com a própria equipe

O plano lista `RT-NOT-001` como dependência, o que sugere **empurrar** o alerta. Ao abrir o
contrato, `NotificacaoApi.enfileirar` exige um **`clienteId`**.

Aquele módulo foi construído para falar com a **cliente**: consentimento, base legal, `ClienteApi`,
LGPD. Um alerta de estoque vai para o **dono**. E o sistema não tem como alcançá-lo:

- `usuario` não tem telefone;
- inventar uma "cliente" que representa o salão para atravessar a fila de mensagens seria contornar
  a LGPD pela porta de trás, num módulo cujo trabalho é justamente respeitá-la.

Então esta rotina entrega a **consulta** — que é o que a tela de estoque lê — e registra a lacuna.
Ela não é desta rotina: é a mesma família do `usuario` sem `profissional_id`, e vai reaparecer em
todo requisito de "avisar o salão" — a fila de espera (RT-AGD-010), o alerta de segurança, o
repasse pronto.

**Resolver isso é rotina do `iam` e do `notificacao`**, e a decisão de fundo é se existe um canal
interno (destinatário = usuário, sem consentimento porque não é dado de titular externo) ou se o
dono é cadastrado como cliente do próprio salão. A primeira é mais limpa; a segunda é mais barata
e confunde para sempre.

## 3. Duas perguntas, uma tela

"O que está acabando?" e "o que vai vencer?" têm causas e ações diferentes — comprar contra usar ou
descartar —, mas quem olha é a mesma pessoa, no mesmo momento do dia. Separá-las em duas telas
garantiria que uma delas nunca fosse aberta.

## 4. Nulo não é zero, pela quinta vez

| `estoque_minimo` | Significado |
|---|---|
| `null` | ninguém definiu — **não alerta** |
| `0` | "avise quando acabar" — decisão comum para o produto de vitrine |
| `10` | alerta em 10 ou menos |

Com `not null default 0`, todo produto nasceria alertando ao zerar e a tela viraria uma lista de
cem linhas. **Um alerta que ninguém lê é pior que nenhum**, porque ocupa o lugar do que importa.

O mínimo tem caso de uso próprio (`DefinirEstoqueMinimoUseCase`), como o preço: ele se ajusta com o
tempo, olhando o giro, e quem faz isso não está refazendo o cadastro.

## 5. O número que faz agir

Cada lote vencendo carrega o **custo em risco**, e a resposta soma tudo. *"R$ 200 vão virar lixo em
10 dias"* move alguém; *"3 lotes vencendo"* não.

E cada produto em falta carrega **quanto falta** para voltar ao mínimo — que é o número do pedido de
compra, não um cálculo que a pessoa tem de fazer de cabeça.

## 6. Testes obrigatórios

- [x] `AlertasDeEstoqueIT` — 14 cenários: **produto sem mínimo não alerta, mesmo zerado** ·
  **mínimo zero alerta ao zerar** · quanto falta é o número do pedido · no mínimo alerta, acima
  não · remover o mínimo tira da lista · mínimo negativo recusado · lote vencendo com custo em
  risco · **vencido entra na mesma lista** · fora da janela não aparece, e aparece com a janela
  alargada · lote sem validade nunca vence · **lote esgotado não alerta** · sem nada a avisar a
  resposta sabe que está vazia · isolamento entre salões · sem `estoque:read` não vê.

## 7. Pendências

- [ ] **O alerta não é empurrado** — ver seção 2. É a pendência da rotina, e ela é de arquitetura,
      não de esforço.
- [x] ~~Não há rota~~ — `GET /api/v1/produtos/alertas` e
      `PUT /api/v1/produtos/{id}/estoque-minimo` foram expostas na RT-EST-009, junto com as rotas
      do leitor. **Tela continua não havendo.**
- [ ] **A janela de validade não é configurável por salão.** É parâmetro da consulta com padrão de
      30 dias; um salão com produto de giro lento vai querer outro número.
- [ ] **O mínimo não considera o giro.** Mínimo fixo é pior que ponto de pedido calculado sobre o
      consumo médio — e agora que a ficha técnica (RT-EST-007) registra consumo, o dado existe.
- [ ] **Não há alerta de ruptura prevista.** "Nesse ritmo a tinta acaba na quinta" é mais útil que
      "a tinta está abaixo do mínimo", e depende do mesmo dado de giro.
- [ ] **Falta de insumo no fechamento (RN-EST-022) ainda só vai para o log** — era para virar
      alerta aqui, e não virou porque exigiria uma tabela de ocorrências.
