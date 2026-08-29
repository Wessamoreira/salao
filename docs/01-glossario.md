# Glossário — linguagem ubíqua

Estes termos são usados **iguais** no código, no banco, na API, na tela e na conversa com o
dono do salão. Em português, sem misturês (`AgendamentoRepository`, não `BookingRepository`).

| Termo | Definição | Tipo no código |
|---|---|---|
| **Estabelecimento** | O tenant. Um salão. Raiz de todo isolamento. Tem fuso, moeda e política de comissão próprios | `Estabelecimento` |
| **Usuário** | Quem faz login. Pertence a um estabelecimento e tem um perfil | `Usuario` |
| **Perfil** | Conjunto nomeado de permissões (`ADMIN`, `GERENTE`, `PROFISSIONAL`, `RECEPCAO`, `PAINEL`, `BOT`) | `Perfil` |
| **Permissão** | Autorização granular no formato `recurso:acao:escopo` | `String` constante |
| **Profissional** | Quem executa serviço. Normalmente PJ, com comissão. Pode ou não ter usuário | `Profissional` |
| **Cliente** | Quem recebe o serviço. Não tem login na v1 | `Cliente` |
| **Serviço** | Item do catálogo: nome, duração, preço, comissão padrão, recursos exigidos, blocos | `Servico` |
| **Bloco de serviço** | Trecho do serviço: `ATIVO` (profissional ocupado) ou `PAUSA` (química agindo) | `BlocoServico` |
| **Recurso** | Ativo escasso: lavatório, cadeira, secador, sala | `Recurso` |
| **Jornada** | Faixas de trabalho do profissional por dia da semana | `Jornada` |
| **Exceção de jornada** | Desvio pontual da jornada: feriado, férias, curso | `ExcecaoJornada` |
| **Agendamento** | Reserva de janelas de tempo de um profissional (e recursos) para um cliente | `Agendamento` |
| **Bloco de agendamento** | Janela concreta reservada. É aqui que a sobreposição é proibida | `BlocoAgendamento` |
| **Bloqueio** | Janela indisponível sem cliente | `Bloqueio` |
| **Encaixe** | Agendamento inserido em janela livre curta, tipicamente durante uma `PAUSA` | — |
| **Fila de espera** | Cliente que quer um horário ocupado e aceita ser chamado se vagar | `FilaEspera` |
| **Comanda** | Registro do atendimento executado: serviços, produtos, descontos, pagamentos | `Comanda` |
| **Item de comanda** | Linha da comanda. Guarda **snapshot** de preço e regra de comissão aplicados | `ItemComanda` |
| **Executante** | Profissional vinculado a um item, com o próprio percentual (ex.: assistente que lava) | `Executante` |
| **Comissão** | Valor devido a um executante por item de comanda | `Comissao` |
| **Fechamento** | Consolidação das comissões de um período em um repasse a um profissional | `Fechamento` |
| **Vale** | Adiantamento pago ao profissional antes do fechamento, descontado nele | `Vale` |
| **Lançamento** | Registro imutável no livro-razão. Estorno é lançamento novo de sinal contrário | `Lancamento` |
| **Pagamento** | Forma e valor com que a comanda foi quitada. Uma comanda pode ter vários | `Pagamento` |
| **Transação de adquirente** | Registro vindo da maquininha/PSP, a conciliar com um pagamento | `TransacaoAdquirente` |
| **Conciliação** | Casamento entre pagamento e transação de adquirente | `Conciliacao` |
| **Produto** | Item físico vendável ou consumível | `Produto` |
| **Lote** | Entrada de produto com custo e validade próprios | `Lote` |
| **Movimento de estoque** | Evento imutável que altera saldo: `ENTRADA`, `VENDA`, `CONSUMO_INTERNO`, `PERDA`, `AJUSTE` | `MovimentoEstoque` |
| **FEFO** | *First Expired, First Out*. Sai primeiro o lote que vence primeiro | — |
| **Conversa** | Sessão de diálogo com um contato em um canal (WhatsApp/Telegram) | `Conversa` |
| **Intenção pendente** | Ação proposta pela IA aguardando confirmação humana, com TTL e máquina de estados | `IntencaoPendente` |
| **Simulação** | Prévia de uma escrita, sem gravar. Antecede toda confirmação | `Simulacao` |

## Termos proibidos

Não use: *booking*, *appointment*, *staff*, *customer*, *order*, *ticket*, *checkout*, *SKU*,
*commission fee*. Não use `Servico` para significar classe de serviço da aplicação — a camada
é `UseCase`, nunca `Service`.
