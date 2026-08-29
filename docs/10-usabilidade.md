# Usabilidade — requisitos, não opiniões

Cada item aqui é **requisito verificável**, com a rotina onde é implementado. Sistema de salão não
é usado por quem tem tempo: é usado com o telefone entre a orelha e o ombro, cliente esperando em
pé e secador ligado ao lado.

## O contexto real de uso

| Fato | Consequência de projeto |
|---|---|
| A recepção atende telefone e cliente presencial ao mesmo tempo | Fluxo de agendar tem que caber sem tirar a mão do teclado |
| O cliente diz "é a Maria" e existem 8 Marias | Busca precisa aceitar telefone parcial, não só nome |
| O profissional atrasa 20 min e tudo depois desloca | Precisa de ação em massa, não de reagendar 6 vezes |
| A internet do salão cai | Leitura da agenda do dia não pode virar tela branca |
| O balcão fica a 2–3 metros do painel | Fonte grande, sem valores, sem detalhe |
| O dono quer ver quanto entrou hoje enquanto anda pelo salão | Um número, na primeira tela, no celular |

---

## R-UX-01 — A agenda é o produto
`RT-AGD-011`

90% do tempo de uso é uma tela. Ela abre em menos de 1s com o dia inteiro visível, sem scroll
horizontal, e todo o resto do sistema é secundário. **Orçamento explícito: p95 < 800ms para
carregar o dia.** Se não couber, o problema é a query ou o modelo, não a máquina do salão.

## R-UX-02 — Agendar sem tirar a mão do teclado
`RT-AGD-011`

A recepção trabalha no teclado. O fluxo completo tem que ser possível assim:

```
/            foca a busca de cliente
digita       busca por nome, apelido ou 4 últimos dígitos do telefone
Enter        seleciona
Tab          vai para o serviço, digita as primeiras letras, Enter
Tab          profissional (já sugerido pelo serviço), Enter
Tab          horário (já sugerido pelo primeiro livre)
Enter        confirma
```

Complementos: `Cmd/Ctrl+K` abre a paleta de comandos ("novo agendamento", "abrir comanda",
"buscar cliente"). `Esc` sempre fecha sem perder o que foi digitado. Ordem de `Tab` previsível.
`Enter` sempre confirma a ação primária.

**Verificação:** teste E2E que executa o agendamento inteiro sem um único evento de mouse.

## R-UX-03 — Busca de cliente pelos 4 últimos dígitos
`RT-CLI-002`

O caso mais comum do balcão é o telefone tocando. Buscar por nome falha (8 Marias, apelidos,
grafia). Buscar pelos 4 últimos dígitos resolve na primeira tentativa. Índice trigram + `unaccent`
para o nome; coluna normalizada E.164 e busca por sufixo para o telefone.

## R-UX-04 — Cadastrar cliente sem sair da tela
`RT-CLI-001`

Cliente novo no telefone não pode obrigar a abandonar o agendamento. "Cliente novo" é inline,
pede **só nome e telefone**, e o resto é completado depois. Todo campo além desses dois é opcional
no cadastro rápido — obrigatoriedade excessiva é o que faz a recepção cadastrar "Maria 2".

## R-UX-05 — Desfazer em vez de confirmar
`RT-AGD-005`, `RT-AGD-003`

Perguntar "tem certeza?" a cada clique treina o usuário a clicar "sim" sem ler, e aí a
confirmação não protege mais nada. Padrão:

- Ação reversível (cancelar agendamento, mover horário, remover item da comanda): **executa na
  hora + toast com "Desfazer" por 8 segundos**.
- Ação irreversível ou com efeito financeiro (fechar comanda, fechamento de período, estorno,
  anonimizar cliente): **confirmação explícita**, com o efeito descrito em número
  ("isso vai gerar R$ 3.480,00 de repasse para 4 profissionais").

## R-UX-06 — Conflito visível durante o arraste, não depois
`RT-AGD-003`, `RT-AGD-011`

No drag & drop, a área de destino fica vermelha **antes de soltar** se houver conflito de
profissional ou de recurso. Descobrir o conflito depois do erro do servidor é o pior dos mundos.
Snap de 5 em 5 minutos. Optimistic update com rollback visível se o servidor recusar.

## R-UX-07 — Comandas abertas sempre visíveis
`RT-ATD-001`, `RT-ATD-008`

Comanda aberta esquecida é dinheiro que sai pela porta. Barra fixa e persistente com as comandas
abertas do dia, contador e valor parcial. Alerta visual passadas 3 horas de abertura.

## R-UX-08 — Fechar comanda em uma tela só
`RT-ATD-008`

Serviços, produtos, desconto e pagamento na mesma tela, sem navegação. **Split de pagamento é
requisito, não extra**: metade cartão, metade pix acontece o tempo todo. O restante a pagar é
recalculado a cada forma adicionada e fica sempre visível.

## R-UX-09 — Empurrar a agenda em massa
`RT-AGD-008`

O profissional atrasou. Uma ação: "atrasar a partir das 14h em 20 minutos", mostrando quais
agendamentos serão movidos e quais entram em conflito **antes** de aplicar. Sem isso a recepção
reagenda seis vezes na mão e erra uma.

## R-UX-10 — Bloqueio por arraste
`RT-AGD-007`

"Vou sair uma hora" tem que ser arrastar na grade e escolher o motivo. Formulário com data,
hora inicial, hora final e descrição para isso é fricção que faz a pessoa não registrar — e
agendamento em cima de bloqueio não registrado vira briga com o profissional.

## R-UX-11 — Densidade configurável
`RT-AGD-011`

Salão com 3 profissionais e salão com 15 precisam de grades diferentes. Zoom de 15/30/60 minutos
por linha, persistido por usuário. Colunas por profissional com scroll horizontal só quando
inevitável.

## R-UX-12 — Status por cor **e** forma
`RT-AGD-011`

Daltonismo é comum e o requisito de contraste AA é obrigatório. Cada status tem cor, ícone e
borda distintos: `SOLICITADO` tracejado, `CONFIRMADO` sólido, `EM_ATENDIMENTO` com barra de
progresso, `CONCLUIDO` esmaecido, `NO_SHOW` hachurado. Cor sozinha nunca carrega informação.

## R-UX-13 — Painel do balcão legível a 3 metros
`RT-AGD-012`

Fonte grande, contraste alto, apenas: horário, cliente, profissional, status. **Zero valor
financeiro.** Auto-scroll quando não couber. Relógio visível. Destaque para quem está atrasado.
Reconecta sozinho e mostra "atualizado às HH:MM" — nunca fica mostrando dado velho em silêncio.

## R-UX-14 — Offline honesto
`RT-AGD-012`, PWA

Cache somente leitura da agenda do dia. Quando cair a conexão, banner claro:
**"Sem conexão — dados de 14:32"**. Não aceite escrita offline na v1: a fila de escrita em cima
de uma agenda com exclusion constraint gera conflito que o usuário não sabe resolver. Mentir
sobre estado é pior que não funcionar.

## R-UX-15 — Telefone é a maior fonte de erro de cadastro
`RT-CLI-001`, `RT-CLI-003`

Normalizar para E.164 no backend. Aceitar colado com máscara, com `+55`, sem DDD (assumir o do
estabelecimento). Detectar duplicado **enquanto digita**, não no submit. Rotina de mesclagem para
quando o duplicado passar mesmo assim — e ele vai passar.

## R-UX-16 — Comprovante para o cliente
`RT-FIN-008`

O cliente pede comprovante. Duas saídas: impressão térmica 80mm (o salão já tem impressora) e
envio pelo WhatsApp. Não é nota fiscal — está fora do escopo da v1 e o texto deixa isso claro.

## R-UX-17 — Onboarding com catálogo pronto
`RT-CAT-001`

Cadastrar 40 serviços do zero na primeira semana é o que faz o salão desistir. Catálogo
pré-carregado de serviços comuns (corte, escova, coloração, hidratação, manicure, pedicure,
progressiva, luzes) com duração e blocos sugeridos, importável com um clique e editável depois.
É o que decide se o sistema é adotado ou abandonado — e é barato de fazer.

## R-UX-18 — Skeleton, nunca spinner de tela cheia
`RT-INF-010`

A estrutura da tela aparece imediatamente; o conteúdo preenche. Nunca bloqueie a tela inteira.
Ação otimista com rollback visível é o que dá a sensação de "sem delay".

## R-UX-19 — O número que o dono quer ver
`RT-FIN-004`

Primeira tela do perfil `ADMIN` no celular: faturamento do dia, comandas abertas, agendamentos
restantes, no-shows. Quatro números. Não é dashboard com oito gráficos — é o que ele olharia no
caderno.

## R-UX-20 — Mensagens de erro em português de gente
`todas`

O front mapeia `codigo` para texto. O texto diz o que aconteceu **e o que fazer**:

| Ruim | Bom |
|---|---|
| "Erro ao processar requisição" | "A Ana já tem atendimento das 10:00 às 11:00. O próximo horário livre é 11:30." |
| "Violação de constraint" | "Este horário acabou de ser preenchido por outra pessoa. Escolha outro." |
| "Acesso negado" | "Só o gerente pode dar desconto acima de 10%. Chame o gerente ou reduza o desconto." |

---

## Acessibilidade — piso obrigatório

Contraste AA (4.5:1 em texto normal) · navegação completa por teclado, sem armadilha de foco ·
`aria-label` em todo controle sem texto visível · alvo de toque ≥ 44px no tablet do balcão ·
foco visível e nunca removido por CSS · `prefers-reduced-motion` respeitado.

## Design — como não parecer tela gerada por IA

Fuja de: gradiente roxo, card com sombra grande em tudo, ícone genérico em cada linha, emoji em
título, espaçamento uniforme de 24px por toda parte.

Referência boa é calendário nativo de sistema operacional: densidade alta, tipografia sóbria,
hierarquia por peso e tamanho — não por caixa colorida. **Cor só onde carrega informação**: status
do agendamento, alerta de validade, divergência financeira. O resto é neutro. Sistema de tokens
próprio (cor, espaçamento, tipografia, densidade) desde a `RT-INF-010`, porque retrofitar tokens
depois de 30 telas não acontece.
