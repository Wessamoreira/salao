---
id: RT-AGD-012
titulo: Painel do balcão (SSE), somente leitura
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-011]
permissoes: [agenda:read:all, agenda:read:own]
eventos: []
regras: [RN-AGD-024]
atualizado_em: 2026-09-11
---

# RT-AGD-012 — Painel do balcão

## 1. Objetivo

A tela pendurada no balcão. Quem está esperando olha e sabe quando é a vez dela.

## 2. Em processo, e NÃO pelo outbox

É a decisão que define a rotina, e ela contraria o instinto.

Os mesmos eventos de domínio já existem em `event_publication` e podem ser **reenviados** quando um
consumidor falha. É exatamente o que não se quer aqui: um painel que, ao reconectar, replayasse os
cancelamentos de ontem mostraria a cliente errada na parede da recepção.

O painel quer o **agora**. Perder um evento porque o navegador estava desconectado é o
comportamento *correto* — quando ele volta, pede o retrato inteiro de novo.

Daí `@TransactionalEventListener` em processo, e `AFTER_COMMIT`: empurrar antes do commit anunciaria
um agendamento que o rollback pode desfazer.

## 3. O aviso não carrega o dado

Duas rotas: `/painel` devolve o retrato, `/painel/eventos` avisa que ele mudou.

Parece um passo a mais e evita a classe de bug mais chata deste tipo de tela. Um stream que manda
**deltas** exige que o cliente aplique todos, na ordem, sem perder nenhum — e quando ele perde um
(reconexão, aba em segundo plano, rede oscilando) a tela fica errada **em silêncio, para sempre**,
numa parede que ninguém olha de perto.

Pedir o retrato de novo é uma consulta barata e sempre correta. E o painel ainda revalida sozinho a
cada minuto: a internet do salão cai (`docs/10-usabilidade`), e é isso que o faz se recuperar de um
stream que morreu sem avisar.

## 4. O tenant separa as conexões

Cada conexão é registrada sob o estabelecimento de **quem a abriu**, fixado na abertura e não a cada
evento. O evento carrega o seu (RN-INF-009), e só chega a quem casa.

Sem esse casamento, o painel de um salão receberia o movimento do outro — o vazamento mais direto
que este sistema poderia ter, porque apareceria numa **tela pendurada na parede**. Tem teste próprio.

## 5. `fetch`, não `EventSource` — e é decisão de segurança

`EventSource` não manda cabeçalho. Autenticar com ele exigiria o token na query string, que fica em
log de proxy, em histórico e em `Referer` — e a regra do projeto é explícita: nunca dado sensível em
parâmetro de URL.

Ler o SSE com o `ReadableStream` do `fetch` custa vinte linhas e mantém o `Authorization`. O
cuidado que a leitura exige está no código: o que sobra depois do último `\n\n` é evento pela
metade e fica para a próxima leitura, senão um evento partido no meio de um pacote TCP vira dois
avisos truncados.

## 6. O que fica de fora, e é a maior parte

O painel é visto por **quem está esperando** — inclusive por clientes que não são as da lista.

| Fora | Por quê |
|---|---|
| Valores | Quanto custa a coloração da Marilda não é assunto de quem está na cadeira ao lado |
| Serviço, observação, telefone | A três metros ninguém lê, e o que ninguém lê só serve para vazar |
| Nome completo | "Marilda Souza Andrade" vira **"Marilda S."** — o primeiro nome basta para ela se reconhecer, e a inicial desempata as duas Marias |
| Concluído e faltas | Quem já foi atendida saiu; quem faltou não precisa ver o próprio nome na parede |

Sobram quatro colunas, que é o que o canvas desenhou e o que se lê de longe.

## 7. Duas correções sobre o canvas

O canvas do painel é **anterior à medição de contraste** feita em RT-CLI-006, e duas escolhas dele
não sobrevivem à medição:

- **Cabeçalho de coluna em `--tinta-tenue`.** Mede 2.53 sobre papel e "não sobrevive a nenhum uso de
  texto". Passou a `--tinta-fraca` (4.67).
- **Situação com o texto colorido pelo status.** Nenhum status passa em AA sobre papel — o melhor dá
  3.25 —, e "cor nunca em texto" é regra do sistema. A cor virou **barra de 4px à esquerda**; o
  texto ficou em `--tinta`. A informação continua colorida, só mudou de lugar.

E forma além de cor, como em toda a tela do produto: "em atendimento" é negrito, "a confirmar" é
itálico. Em preto e branco, o painel continua legível.

## 8. Testes

- [x] `PainelDeEventosTest` — 5: **o painel de um salão não recebe o movimento do outro** · todos
      os painéis do mesmo salão recebem · salão sem painel não estoura · **um painel desligado não
      derruba o aviso dos outros** · desconectar a última conexão tira o salão do mapa
- [x] `Painel.test.tsx` — 6: as quatro colunas · a situação por extenso em minúsculas · **diz
      quando foi atualizado e quantos faltam** · concorda o singular · dia sem nada explica ·
      **sem conexão avisa em vez de tela em branco**

Contraste medido no navegador: cabeçalho 4.67, cliente 15.77, profissional 4.67, situação 15.77,
rodapé 4.67 — todos acima do mínimo, com o limiar de texto grande aplicado onde vale.

Quando a conexão cai depois que o retrato foi carregado, as linhas não somem: o cache em memória
continua na parede e uma faixa informa `Sem conexão — dados de HH:MM`. Se nenhum retrato chegou, a
mensagem de indisponibilidade ocupa o painel em vez de uma tela branca. O app shell é servido pelo
service worker da casca, mas uma abertura a frio ainda exige a sessão do modo quiosque pendente.

## 9. Pendências

- [ ] **`onCompletion` e `onTimeout` não têm teste.** O disparo é contrato do Spring com o
      container e não acontece num emissor solto; o que está testado é a limpeza em si e o caminho
      de erro de escrita, que é o que de fato acontece
- [ ] **Nada mede quantas conexões existem.** `salõesConectados` existe para o teste e não vai para
      métrica — um vazamento de emissor só apareceria como memória subindo devagar
- [ ] **O painel exige login.** Uma tela de parede que pede senha depois de cada reinício é o tipo
      de coisa que faz o salão deixar o navegador aberto para sempre. Precisa de um modo quiosque
      com credencial própria e permissão só de leitura
- [ ] **Sem reconexão explícita.** Quando o stream cai, quem salva é a revalidação de um minuto —
      funciona, mas com até um minuto de atraso. Um *backoff* de reconexão fecharia essa janela
- [ ] **A hora do relógio grande vem do navegador**, não do servidor. Um painel com o relógio
      errado é pior que um painel sem relógio, e a tela da parede é justamente a que ninguém acerta
