# ADR-0006: WhatsApp Business Cloud API oficial

- **Status:** aceita · **Data:** 2026-08-28

## Contexto

O agente conversacional é diferencial do produto. Existem bibliotecas não-oficiais (baileys,
venom, wppconnect) que automatizam o WhatsApp Web sem custo por mensagem.

## Opções consideradas

| Opção | Prós | Contras |
|---|---|---|
| Biblioteca não-oficial | Sem custo por conversa; sem burocracia | Viola os termos; **derruba o número do salão**. O número é o ativo do negócio |
| **Cloud API oficial (Meta)** | Suportada, com SLA, templates, botões | Custo por conversa; verificação de negócio; janela de 24h |
| API de BSP (Twilio, 360dialog) | Menos burocracia inicial | Custo maior; mais um intermediário |

## Decisão

Cloud API oficial da Meta, atrás de um adapter no módulo `notificacao`. Telegram implementa o
mesmo port — serve de plano B e de ambiente de teste do agente.

## Consequências

**Positivas.** Não existe cenário em que o número do salão seja banido por uso do sistema. Sem
isso não há produto para vender.

**Negativas, assumidas.** Custo por conversa entra no orçamento (pergunta 14). Fora da janela de
24h só se envia template aprovado. **A verificação de negócio e a aprovação de template levam
dias e são assíncronas — o cadastro na Meta começa na semana 1 da Fase 0, não na Fase 4**
(risco R-03).

## Nota de implementação — RT-CNV-006 (2026-09-14)

**O adaptador de envio não ficou na `notificacao`.** O número do salão (`canal_whatsapp`) mora em
`conversacional`, desde RT-CNV-001. Um adaptador na `notificacao` precisaria ler de lá, enquanto
`conversacional` precisaria mandar por ele: dependência circular, que o teste de fronteira recusa. O
port de envio é `EnviadorDoWhatsApp`, em `conversacional`. A notificação à cliente, quando tiver
provedor, pode usá-lo por um `Mensageiro` implementado em `conversacional` — que depende da API da
`notificacao`, e não o contrário.

A decisão desta ADR — Cloud API oficial — não muda. Nenhum provedor está contratado: a saída espera na
fila. Fora da janela de 24h, o pedido de aceite à profissional precisa de modelo aprovado com botão de
resposta rápida, e o webhook já entende os dois formatos de toque (interativo e de modelo).

