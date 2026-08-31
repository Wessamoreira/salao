---
id: RT-EQP-001
titulo: Cadastro de profissional PJ (documento, contrato, comissão padrão)
modulo: equipe
fase: 1
perfil: completo
status: implementado
depende_de: [RT-IAM-007]
permissoes: [usuario:manage]
eventos: []
regras: [RN-EQP-001, RN-EQP-002, RN-EQP-003, RN-EQP-004, RN-EQP-005]
atualizado_em: 2026-08-30
---

# RT-EQP-001 — Cadastro de profissional

## 1. Objetivo

Registrar quem trabalha no salão, sob qual vínculo, com qual documento e qual comissão. Abre o
módulo `equipe`, do qual a agenda inteira vai depender.

## 2. O vínculo não é rótulo: ele decide o resto

A Lei 13.352/2016 — a "Lei do Salão-Parceiro" — criou o arranjo que hoje é o mais comum no setor.
Ela impõe duas coisas que o cadastro precisa saber:

**O parceiro é pessoa jurídica.** Daí CNPJ obrigatório (RN-EQP-002). Não é preferência de cadastro:
um "parceiro" com CPF descreve exatamente o vínculo empregatício disfarçado que a lei existe para
evitar — e que uma fiscalização procura. Empregado e autônomo são pessoa física, e portanto CPF.

**A parceria exige contrato escrito**, homologado pelo sindicato ou assinado diante de duas
testemunhas. Sem ele, a relação pode ser reclassificada como emprego, com encargos retroativos. É a
falha mais cara do modelo, e a única pista que um sistema tem é uma data em branco.

Por isso `contratoAssinadoEm` existe e `contratoPendente()` é calculado.

## 3. Avisar, não bloquear — de novo

O cadastro **passa** sem o contrato (RN-EQP-003).

Bloquear faria o salão simplesmente não cadastrar o profissional. Aí perde-se o profissional
**e** continua-se sem o contrato — o pior dos dois mundos. Registrar a ausência é o que permite
avisar depois.

É o mesmo raciocínio de RN-CLI-016 (duplicado de cliente) e de RN-CLI-005 (telefone repetido):
recusar cadastro legítimo ensina a contornar, e o contorno é sempre pior.

## 4. Documento com dígito verificador conferido

Documento errado só aparece quando alguém precisa dele: emitir a nota, pagar a comissão, responder
a uma fiscalização. Aí já está no cadastro há meses e ninguém lembra do que digitou.

Conferir na entrada custa vinte linhas e pega o erro com a pessoa ainda na frente.

**O caso que o algoritmo sozinho não pega:** `111.111.111-11` **passa** no cálculo do dígito
verificador, e todos os outros repetidos também. É o que se digita quando é preciso preencher o
campo sem ter o documento em mãos — e por isso é recusado explicitamente (RN-EQP-001).

**O erro que eu cometi e o teste pegou:** a primeira versão aplicava o peso do CPF na direção
contrária — 10 no dígito mais à direita em vez do mais à esquerda. **Nenhum CPF válido passava.**
Um teste que só verificasse "recusa lixo" teria passado com o algoritmo invertido; foram os
números sabidamente válidos que mostraram.

Depois disso, um segundo erro do mesmo tipo: escrevi um teste para o caso de dígito verificador
zero usando um número que **supus** ter essa propriedade, sem calcular. Não era válido. Os dois que
estão lá agora foram calculados pelo próprio mod-11.

Os números do teste são gerados pelo algoritmo, não de pessoas reais: pôr um CPF de verdade num
arquivo versionado seria publicar dado pessoal de alguém no repositório.

## 5. Unicidade real, ao contrário do cliente

`(estabelecimento_id, documento)` é único (RN-EQP-004).

É o oposto da decisão de RT-CLI-001, e por um motivo concreto: duas pessoas não compartilham CPF, e
duas fichas com o mesmo CNPJ dividiriam a comissão de um só em dois extratos. Já mãe e filha
compartilham telefone o tempo todo.

O mesmo documento **pode** existir em salões diferentes: profissional que atende em dois lugares é
comum, e cada salão tem o próprio cadastro.

## 6. Desativar, nunca apagar

O histórico aponta para quem executou cada serviço e cada comanda. Apagar quebraria o extrato de
comissão — inclusive o de meses já pagos, que é justamente o que alguém vai querer conferir quando
houver dúvida.

Mesma decisão de RT-IAM-007 para usuários. A listagem administrativa mostra os inativos, com os
ativos primeiro: quem administra precisa ver quem saiu, para trazer de volta e para entender um
extrato antigo.

Desativar duas vezes não é erro: duas abas abertas chegam nesse caso, e recusar diria que algo deu
errado quando o estado desejado já é o que existe.

## 7. A regra vale no banco, não só no domínio

`profissional_documento_do_vinculo` é uma `check constraint`, além da validação no caso de uso.

O caso de uso protege o caminho normal; a restrição protege o dia em que alguém inserir por script,
migração ou correção manual — que é onde regra costuma ser burlada sem ninguém perceber.
Verificada do jeito certo: inserindo um parceiro com CPF direto pela conexão de owner e conferindo
que o banco recusa.

## 8. Comissão é `numeric`, nunca ponto flutuante

Meio ponto percentual sobre o faturamento de um ano é dinheiro de verdade, e o erro de
arredondamento aparece como centavo que não fecha no extrato do profissional. `numeric(5,2)` no
banco, `BigDecimal` no código — a regra do projeto vale aqui inteira.

## 9. Testes

- [x] `DocumentoTest` — 17: CPF e CNPJ com e sem máscara · **recusa dígito verificador errado** ·
      **recusa repetidos, que passam no cálculo** · recusa tamanho errado · guarda dígitos e
      formata para exibir · **dígito verificador zero** · raiz repetida de CNPJ é válida
- [x] `CadastroDeProfissionalIT` — 16: cadastra parceiro completo · **parceiro sem contrato é
      cadastrado e fica pendente** · só parceria exige contrato · **parceiro com CPF é recusado** ·
      empregado com CNPJ é recusado · documento inválido · documento único no salão · mesmo
      documento em salões diferentes · comissão fora da faixa · desativar mantém a ficha ·
      desativar é idempotente · reativar · isolamento entre salões · trilha sem dado pessoal ·
      **a regra do vínculo vale no banco** · sem permissão é recusado

## 10. Pendências

- [ ] **Não fecha a pendência de RT-IAM-007 ainda.** O perfil `PROFISSIONAL` continua sem ligação
      com esta ficha: falta a coluna `usuario_id` e a decisão de o que acontece quando um
      profissional é desativado e o usuário dele continua entrando. Precisa vir antes de a agenda
      filtrar "minha agenda" (`agenda:read:own`)
- [ ] **Não há tela.** Só a API — o cadastro depende de alguém com acesso técnico. Vale junto de
      RT-EQP-002, para não fazer duas telas de equipe
- [ ] **Editar não existe**, só cadastrar e ativar/desativar. Mesma lacuna que RT-CLI-007 fechou
      para cliente, e pela mesma razão vai aparecer: corrigir uma comissão digitada errada é
      rotina
- [ ] **A comissão é uma só, por profissional.** O plano prevê preço por serviço (RT-CAT-002) e
      override de duração (RT-CAT-004); comissão diferente por serviço é pedido comum e não está
      no plano
- [ ] **Nada avisa sobre contrato vencido.** `contrato_fim` é gravado e ninguém olha. Quando
      houver tela, um parceiro com contrato vencido precisa aparecer tão claramente quanto um sem
      contrato
