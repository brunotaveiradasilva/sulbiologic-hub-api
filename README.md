# Almoxarifado API

API em Java (Spring Boot) + MySQL para o [Almoxarifado em Agenda](https://github.com/brunotaveiradasilva/almoxarifado):
guarda materiais e agendamentos num banco de verdade, para acessar os mesmos dados de qualquer computador —
não só do navegador onde foram cadastrados.

## Stack

- Java 21
- Spring Boot 3 (Web, Data JPA, Validation, Actuator, Security)
- MySQL 8
- Login com token JWT (implementação própria, sem lib externa — ver `auth/JwtService.java`)
- Docker / docker compose para rodar local e fazer deploy

## Rodando local

### Opção 1 — Docker (não precisa instalar Java nem MySQL)

```bash
docker compose up --build
```

Sobe o MySQL e a API juntos. A API fica em `http://localhost:8080`, já com um login
`admin` / `admin123` criado sozinho na primeira vez que sobe (ver seção **Login** abaixo).

### Opção 2 — Java + Maven na máquina

Precisa de JDK 21 e Maven instalados, e um MySQL rodando (pode ser o do `docker compose up mysql`
sozinho). Copie `.env.example` para `.env`, ajuste se precisar, exporte as variáveis e rode:

```bash
mvn spring-boot:run
```

## Login

A API inteira exige login — só `POST /api/auth/login` fica aberto, o resto sempre precisa de um
token válido no header `Authorization: Bearer <token>`.

Na primeira vez que a API sobe sem nenhum usuário cadastrado, ela cria um login sozinha a partir
de `ADMIN_USERNAME` / `ADMIN_PASSWORD`. Se `ADMIN_PASSWORD` não estiver definida, gera uma senha
aleatória e mostra ela **uma única vez** no log de inicialização (procure por `Nenhum usuario
existia ainda` nos logs do Railway/`docker compose logs`).

```bash
# entrar
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"usuario":"admin","senha":"admin123"}'
# -> {"token":"...","usuario":"admin","role":"ADMIN"}

# criar outro login (precisa estar autenticado como ADMIN)
curl -X POST http://localhost:8080/api/auth/usuarios \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"usuario":"maria","senha":"uma-senha-forte"}'
```

Não existe endpoint de auto-cadastro público de propósito: só quem já tem login pode criar outro.

Todo login tem um papel (`role`): `ADMIN` ou `USUARIO`. O login criado pelo bootstrap
(`ADMIN_USERNAME`) sempre vira `ADMIN`; qualquer login criado depois nasce `USUARIO`. Só `ADMIN`
consegue criar/excluir outros logins e gerenciar representantes e tipos de meta — o resto da API
(materiais, agendamentos, trocar a própria senha) continua liberado pra qualquer login autenticado.

Dentro do app, tudo isso (listar, criar, excluir logins e trocar a própria senha) já tem tela —
não precisa usar `curl` no dia a dia, é só pra quando ninguém consegue mais entrar (próxima seção).

### Recuperando o acesso

Se ninguém souber a senha do `admin` (ex: foi a gerada automaticamente e o log já rolou), defina
no serviço:

```
RESET_ADMIN_PASSWORD=true
ADMIN_PASSWORD=uma-senha-nova-que-voce-escolheu
```

e reinicie/redeploy a API. No próximo boot ela redefine a senha desse login para o valor de
`ADMIN_PASSWORD`, mesmo que ele já exista. **Depois de entrar, apague ou volte `RESET_ADMIN_PASSWORD`
para `false`** — senão a senha é redefinida de novo a cada restart.

## Endpoints

Todos sob o prefixo `/api`. Corpos e respostas em JSON, no mesmo formato usado pelo front-end
(`types.ts`). Todos exigem login (seção acima), exceto `/api/auth/login`.

| Método | Rota                        | Descrição                                   |
|--------|------------------------------|----------------------------------------------|
| POST   | `/api/auth/login`            | Login — devolve o token JWT                   |
| GET    | `/api/auth/usuarios`         | Lista os nomes de usuário cadastrados         |
| POST   | `/api/auth/usuarios`         | Cria outro login (exige ser ADMIN)            |
| DELETE | `/api/auth/usuarios/{usuario}` | Exclui um login (exige ser ADMIN; nunca o último que resta) |
| PATCH  | `/api/auth/senha`            | Troca a própria senha (`{"senhaAtual","novaSenha"}`) |
| PATCH  | `/api/auth/avatar`           | Troca a própria foto de perfil (`{"avatar"}`, data URL base64; manda vazio/nulo pra remover) |
| GET    | `/api/materiais`             | Lista todos os materiais                      |
| POST   | `/api/materiais`             | Cria um material                              |
| PUT    | `/api/materiais/{id}`        | Atualiza um material                          |
| DELETE | `/api/materiais/{id}`        | Exclui um material                            |
| GET    | `/api/agendamentos`          | Lista todos os agendamentos                   |
| POST   | `/api/agendamentos`          | Cria um agendamento                           |
| PUT    | `/api/agendamentos/{id}`     | Atualiza um agendamento                       |
| PATCH  | `/api/agendamentos/{id}/status` | Só troca o status (`{"status": "retirado"}`) |
| DELETE | `/api/agendamentos/{id}`     | Exclui um agendamento                         |
| GET    | `/api/fornecedores`          | Lista os fornecedores (exige ser ADMIN)       |
| POST   | `/api/fornecedores`          | Cria um fornecedor (exige ser ADMIN)          |
| PUT    | `/api/fornecedores/{id}`     | Atualiza um fornecedor (exige ser ADMIN)      |
| DELETE | `/api/fornecedores/{id}`     | Exclui um fornecedor (exige ser ADMIN; recusa se ele tiver metas) |
| GET    | `/api/representantes`            | Lista os representantes, com os fornecedores de cada um (exige ser ADMIN) |
| POST   | `/api/representantes`            | Cria um representante (`{"nome","fornecedorIds","email","celular","codigoAds"}`, `codigoAds` é opcional — ver **Integração com a ADS** — exige ser ADMIN) |
| PUT    | `/api/representantes/{id}`       | Atualiza um representante (exige ser ADMIN)        |
| DELETE | `/api/representantes/{id}`       | Exclui um representante (exige ser ADMIN)          |
| GET    | `/api/metas`                 | Lista as metas na ordem escolhida pelo admin (sem ordem vão pro fim, por nome), com o fornecedor de cada uma (exige ser ADMIN) |
| POST   | `/api/metas`                 | Cria uma meta (`{"nome","fornecedorId","unidade","codigoAdsDivisao","cnpjAdsFornecedor","produtosIncluidos","produtosExcluidos"}`, `unidade` é `KG`, `UNIDADE`, `REAL` ou `CLIENTES`; os códigos ADS e produtos são opcionais; entra no fim da ordem — ver **Integração com a ADS** — exige ser ADMIN) |
| PUT    | `/api/metas/{id}`            | Atualiza uma meta (exige ser ADMIN)           |
| PUT    | `/api/metas/ordem`           | Grava a ordem das metas na tela (`{"ids":[...]}`, na ordem desejada; as que faltarem vão pro fim) e devolve todas já ordenadas (exige ser ADMIN) |
| DELETE | `/api/metas/{id}`            | Exclui uma meta (exige ser ADMIN)             |
| GET    | `/api/metas-representante?mes=2026-09` | Lista os valores de meta atribuídos aos representantes — de todos os meses, ou só do `mes` pedido (exige ser ADMIN) |
| GET    | `/api/metas-representante/totais-vendidos` | Total vendido por representante e mês (card "Total vendido"), vindo da ADS (exige ser ADMIN) |
| POST   | `/api/metas-representante/copiar` | Copia os valores de meta de um mês pro outro, só onde o destino ainda não tem valor (`{"de":"2026-08","para":"2026-09","fornecedorId"}`, `fornecedorId` opcional; exige ser ADMIN) |
| POST   | `/api/metas-representante/sincronizar?mes=2026-09` | Força agora o recálculo do realizado a partir da ADS, do `mes` pedido ou do atual (exige ser ADMIN) |
| POST   | `/api/metas-representante`        | Atribui um valor de meta a um representante (`{"representanteId","metaId","mes","valorMeta"}` — `mes` vazio vale o mês atual; exige ser ADMIN) |
| PUT    | `/api/metas-representante/{id}`   | Atualiza um valor de meta; o `valorRealizado` não muda por aqui (exige ser ADMIN) |
| DELETE | `/api/metas-representante/{id}`   | Exclui um valor de meta (exige ser ADMIN)     |
| GET    | `/api/dados/vendas?inicio=2025-09-01&fim=2025-09-30&representanteId=&fornecedorId=` | Vendas de um período (até 1 ano) direto da ADS, por representante do cadastro e no total: R$, kg e clientes positivados. `representanteId` e `fornecedorId` opcionais e aceitam vários (repetindo o parâmetro ou separados por vírgula) — ver **Aba Dados** (exige ser ADMIN) |
| POST   | `/api/interno/sincronizacao-diaria` | A mesma sincronização das 6h (metas e Especialista Pet), pro Cloud Scheduler chamar. Sem login: exige o header `X-Cron-Token` igual a `CRON_TOKEN` (sem `CRON_TOKEN` configurado dá `404`) |
| GET    | `/actuator/health`           | Health check (usado pelo Railway/Render/Cloud Run), sem login |

## Variáveis de ambiente

| Variável                | Padrão (local)                              | Para que serve                         |
|--------------------------|----------------------------------------------|------------------------------------------|
| `PORT`                   | `8080`                                        | Porta HTTP da API                        |
| `DB_URL`                 | `jdbc:mysql://localhost:3306/almoxarifado`    | URL JDBC do MySQL                        |
| `DB_USERNAME`             | `almoxarifado`                               | Usuário do banco                         |
| `DB_PASSWORD`             | `almoxarifado`                               | Senha do banco                           |
| `CORS_ALLOWED_ORIGINS`   | `http://localhost:5173,https://brunotaveiradasilva.github.io` | Origens que podem chamar a API, separadas por vírgula |
| `JWT_SECRET`             | chave de desenvolvimento (fraca, só local)   | Assina os tokens — **troque por um valor forte e único antes de publicar** (ex: `openssl rand -base64 48`) |
| `JWT_VALIDADE_HORAS`     | `168` (7 dias)                                | Por quanto tempo um login fica valendo sem precisar entrar de novo |
| `ADMIN_USERNAME`         | `admin`                                       | Nome do primeiro login, criado sozinho se o banco não tiver nenhum usuário |
| `ADMIN_PASSWORD`         | *(gera uma aleatória e loga se não definir)*  | Senha do primeiro login                  |
| `RESET_ADMIN_PASSWORD`   | `false`                                       | `true` força redefinir a senha de `ADMIN_USERNAME` no próximo boot, mesmo que já exista — ver **Recuperando o acesso** |
| `ADS_API_URL`            | `https://adsapi.com.br`                       | URL base da API da ADS (histórico de vendas) — produção, não homologação |
| `ADS_API_KEY`            | *(vazio)*                                     | Header `x-api-key` da API da ADS — ver **Integração com a ADS** |
| `ADS_USER_AGENT`         | `SulBiologic`                                 | Header `User-Agent` exigido pela API da ADS |
| `ADS_ESPECIFICO_ID`      | `074`                                          | Valor fixo da conta, exigido em toda chamada de histórico de vendas (não é um filtro) |
| `ADS_CNPJ_DISTRIBUIDORA` | *(vazio)*                                    | CNPJ da distribuidora, usado como path param nas chamadas à ADS |
| `AGENDADOR_INTERNO`      | `true`                                        | Roda os jobs das 6h dentro da API. `false` no Cloud Run, onde quem chama é o Cloud Scheduler — ver **Deploy (Cloud Run + TiDB)** |
| `CRON_TOKEN`             | *(vazio: rota desligada)*                     | Token que o Cloud Scheduler manda no header `X-Cron-Token` pra `POST /api/interno/sincronizacao-diaria` (ex: `openssl rand -hex 32`) |

## Integração com a ADS (histórico de vendas)

O `valorRealizado` das metas (`/api/metas-representante`) é calculado sozinho a partir do
histórico de vendas da [API da ADS](https://adsapi.com.br/api/v1) — não precisa mais editar esse
valor na mão (e nem dá: POST/PUT ignoram o `valorRealizado`, ele só muda pela sincronização).

**Metas por mês:** cada valor de meta vale pra um mês (`mes`, formato `2026-09`), já que a meta de
um representante pode mudar de um mês pro outro. Ficam na tabela `metas_representante_mensal`,
com um registro por representante + meta + mês. A tabela antiga, `metas_representante` (sem mês),
é copiada pra nova como o mês em que a API subiu e depois apagada, uma vez só, no boot
(`MigracaoMetasPorMes`). O "mês atual" segue o horário de Brasília.

**Mês fechado:** quando o mês acaba, as metas dele ficam só pra consulta — em outubro não dá mais
pra criar, editar, excluir nem copiar metas pra setembro (a API responde `409`, ver
`Mes.garantirAberto`). O mês atual e os meses futuros continuam abertos. O realizado não entra
nessa regra: ele continua vindo da ADS (inclusive o do mês anterior, do dia 1 ao 5).

**Autenticação:** confirmada testando direto contra a API de produção — só os headers
`x-api-key` e `User-Agent`, sem login nem Bearer token (a doc/spec da ADS não documenta isso; o
ambiente de homologação, `hom.adsapi.com.br`, nem aceita essa autenticação — use sempre
`adsapi.com.br`, produção).

**`especificoid`:** é um valor fixo da conta (`ADS_ESPECIFICO_ID`, ex: `074`), não um filtro —
testado com outros valores e todos deram `400`. Vai sempre igual em toda chamada.

**Como cada representante/meta se liga à ADS:**

- Cada **Representante** tem um campo opcional `codigoAds`, preenchido na tela de cadastro —
  é o `codigo` dele na ADS (`GET /api/v1/{cnpj}/representantes`), usado como `repr_id` na consulta
  de histórico de vendas.
- Cada **Meta** tem dois campos opcionais, preenchidos na tela de cadastro — só um dos dois é
  usado por meta (`cnpjAdsFornecedor` tem prioridade se os dois estiverem preenchidos):
  - `codigoAdsDivisao`: código (ou vários, separados por vírgula, ex: `112,113`) da divisão
    correspondente na ADS (`GET /api/v1/{cnpj}/divisoes`) — soma só os itens vendidos nessa(s)
    divisão(ões). Pra metas específicas de uma linha de produto (ex: "Cookie", "Umidos").
  - `cnpjAdsFornecedor`: CNPJ do fornecedor na ADS (`GET /api/v1/{cnpj}/fornecedores`, campo
    `cnpjCpf`) — soma tudo vendido desse fornecedor, sem filtrar por divisão. Pra metas
    "catch-all" tipo "Geral", que somam o fornecedor inteiro.
- Cada **Meta** também pode ter `produtosExcluidos`: produtos que não contam pra ela, separados
  por vírgula. Só dígitos é o `itens[].produto.id` da ADS (ex: `5085`); texto é um trecho de
  `itens[].produto.descricao`, sem diferenciar maiúscula (ex: `WELLPET` tira todas as
  apresentações). Vale com `cnpjAdsFornecedor` ou `codigoAdsDivisao`, e em todas as unidades
  (inclusive positivação: cliente que só comprou produto excluído não conta).
- E `produtosIncluidos`, no mesmo formato: se preenchido, **só** esses produtos contam (ex: uma meta
  sazonal do Banni com os códigos das apresentações dele). Pode ser usado sozinho, sem CNPJ nem
  divisão, ou junto com eles; os excluídos continuam saindo mesmo se também estiverem incluídos.
  Um incluído pode ter fator: `4931*3` (ou `4931x3`, ou `FLACONETES*3` por nome) faz cada unidade
  vendida contar 3 nas metas `UNIDADE` — pra kits que a ADS manda como quantidade 1 (ex: Banni
  "C/ 3 FLACONETES"). Não muda R$, kg nem positivação.
- Representante ou meta sem nenhum desses campos preenchidos simplesmente não são sincronizados
  (o `valorRealizado` deles fica em 0 — ele não é editável na mão).

**Cálculo:** `AdsSincronizacaoService` busca, uma vez por representante (com `codigoAds`
preenchido), todo o histórico de vendas do mês sincronizado (dia 1 até o fim do mês, ou até hoje no mês atual)
filtrado por `repr_id`.
Pra cada meta atribuída a esse representante, soma por `cnpjAdsFornecedor` (toda venda desse
fornecedor) ou por `codigoAdsDivisao` (só os itens cuja `divisao.id` bate com um dos códigos).
Também soma **todo** o histórico do mês (todos os fornecedores e divisões, sem filtro nenhum) e
salva em `TotalVendidoMensal` (um por representante e mês; no mês atual também em
`Representante.totalVendidoAds`, o campo antigo) — é esse número que a tela mostra no card "Total
vendido" (por isso pode ser maior que a soma das metas: uma meta "Geral" por fornecedor, por
exemplo, já duplica o que outra meta mais específica desse mesmo fornecedor também conta).

| Unidade da meta | Campo somado |
|---|---|
| `REAL` | `itens[].valores.valorProduto` |
| `KG` | `itens[].peso.bruto` (e também soma `valores.valorProduto` dos mesmos itens em `realizadoEmReais`, que a tela mostra embaixo do nome da meta) |
| `UNIDADE` | `itens[].quantidade` |

Cada pedido pesa diferente na soma dependendo do campo `operacao` que a ADS manda: pedidos
`"VENDA DE MERCADORIA"` somam normal, os que têm `"DEV"` no nome (devolução) **descontam**, e
bonificação (`"BONIFICACAO CREDITO"`, `"BONIFICACAO TROCA"` — brinde/troca promocional, não é
venda de verdade) **não conta nem soma nem desconta**. Qualquer operação não reconhecida também
fica de fora, por segurança (ver `AdsSincronizacaoService.sinal`).

**Quando roda:** todo dia às 6h de Brasília (`@Scheduled` em `AdsSincronizacaoService`),
recalculando o mês atual inteiro do zero (não é incremental). Do dia 1 ao dia 5 também refaz o mês
anterior, pra fechar ele com o que a ADS lança atrasado. Também dá pra forçar na hora, de qualquer
mês: `POST /api/metas-representante/sincronizar?mes=2026-09` (exige ser ADMIN; devolve as
atribuições atualizadas).

Se a ADS estiver fora do ar ou recusar a chamada pra um representante, esse representante fica de
fora do recálculo daquela vez (loga um aviso) — os outros continuam normalmente.

### Aba Dados (comparativo de períodos)

`GET /api/dados/vendas` não lê nada do que foi sincronizado: consulta o histórico da ADS do
período pedido na hora (`AdsVendasPeriodoService`). Só entram os representantes do cadastro com
`codigoAds` preenchido — cada um buscado com `repr_id` (igual à sincronização das metas), 4 ao
mesmo tempo —, então cada linha já é o representante do cadastro. Com `representanteId`, busca só
eles (vários: repita o parâmetro ou separe por vírgula). Representante sem `codigoAds` fica de fora (se nenhum escolhido tiver, dá `400`). Por isso dá pra
comparar com qualquer mês do passado, mesmo sem meta cadastrada nele. A regra de operação é a mesma
das metas (venda soma, devolução desconta, bonificação fica de fora). Se a ADS falhar em qualquer
representante, a consulta toda dá `502` — número pela metade enganaria a comparação.

Com `fornecedorId`, só contam os pedidos dos `cnpjAdsFornecedor` e os itens das `codigoAdsDivisao`
das metas desse fornecedor (produtos incluídos/excluídos não entram — é o fornecedor inteiro). Com
vários, junta os códigos de todos — item que bate com mais de um conta uma vez só. Fornecedor
escolhido sem nenhum desses códigos nas metas dá `400`, com o nome dele na mensagem.

Período que terminou há mais de 5 dias fica guardado em memória por 6 horas, por representante
(até 160 combinações de representante e período), porque buscar um ano inteiro na ADS demora.

## Deploy (Cloud Run + TiDB)

Fica dentro das cotas gratuitas permanentes: a API roda no [Cloud Run](https://cloud.google.com/run)
(Google Cloud, o mesmo projeto do Firebase), o banco é um [TiDB Cloud Serverless](https://tidbcloud.com)
(compatível com MySQL) e o [Cloud Scheduler](https://cloud.google.com/scheduler) dispara a
sincronização diária com a ADS. O Google exige cartão cadastrado (plano Blaze), mas só cobra o que
passar da cota. Configure um alerta de orçamento em **Faturamento → Orçamentos e alertas** pra ser
avisado se isso acontecer.

O Cloud Run desliga a API quando ninguém usa, então o primeiro acesso depois de um tempo parado leva
uns 10–20 s. Pelo mesmo motivo os `@Scheduled` das 6h não disparariam sozinhos: lá eles ficam
desligados (`AGENDADOR_INTERNO=false`) e o Cloud Scheduler chama
`POST /api/interno/sincronizacao-diaria`, autenticado pelo header `X-Cron-Token`.

**1. Banco (TiDB).** Crie um cluster *Serverless* (ou *Starter*) em [tidbcloud.com](https://tidbcloud.com),
numa região da AWS perto da do Cloud Run (ex.: `us-east-1` com o Cloud Run em `us-east1`). Em
**Connect**, gere a senha e anote host, porta (4000) e usuário (`xxxx.root`). Crie o banco com
`CREATE DATABASE almoxarifado;` no SQL Editor. A conexão exige TLS, por isso a `DB_URL` leva os
parâmetros de SSL:

```
jdbc:mysql://HOST:4000/almoxarifado?sslMode=VERIFY_IDENTITY&enabledTLSProtocols=TLSv1.2,TLSv1.3
```

As tabelas são criadas sozinhas no primeiro boot (`ddl-auto: update`). Pra levar os dados de um
MySQL antigo, exporte com `mysqldump --no-tablespaces --set-gtid-purged=OFF ... > backup.sql` e
importe no TiDB (`mysql --ssl-mode=VERIFY_IDENTITY -h HOST -P 4000 -u USUARIO -p almoxarifado < backup.sql`)
**antes** de subir a API.

**2. API (Cloud Run).** Com o [gcloud](https://cloud.google.com/sdk/docs/install) instalado e logado
(`gcloud auth login`, `gcloud config set project SEU_PROJETO`), crie um `env.yaml` (está no
`.gitignore`, não suba ele):

```yaml
DB_URL: "jdbc:mysql://HOST:4000/almoxarifado?sslMode=VERIFY_IDENTITY&enabledTLSProtocols=TLSv1.2,TLSv1.3"
DB_USERNAME: "xxxx.root"
DB_PASSWORD: "..."
CORS_ALLOWED_ORIGINS: "https://brunotaveiradasilva.github.io"
JWT_SECRET: "..."        # openssl rand -base64 48
ADMIN_USERNAME: "admin"
ADMIN_PASSWORD: "..."
ADS_API_KEY: "..."
ADS_CNPJ_DISTRIBUIDORA: "..."
AGENDADOR_INTERNO: "false"
CRON_TOKEN: "..."        # openssl rand -hex 32
```

E publique direto do código-fonte (o Cloud Build usa o `Dockerfile`):

```bash
gcloud run deploy almoxarifado-api --source . --region us-east1 \
  --allow-unauthenticated --env-vars-file env.yaml \
  --memory 1Gi --cpu 1 --cpu-boost --max-instances 1 --timeout 1800
```

- `--max-instances 1`: o progresso da sincronização e o cache do comparativo de vendas ficam em
  memória, então tudo precisa cair na mesma instância. De quebra, segura o gasto.
- `--timeout 1800`: a sincronização com a ADS pode levar minutos, e o Cloud Run só dá CPU enquanto a
  requisição está aberta.

A URL que o comando imprime (`https://almoxarifado-api-xxxx.us-east1.run.app`) é o `VITE_API_URL`
do front-end (secret do repositório `sulbiologic-hub` no GitHub; rode o workflow de deploy de novo
depois de trocar). Pra atualizar a API depois, é o mesmo `gcloud run deploy`. Em **Artifact Registry**,
uma política de limpeza que mantém só as últimas imagens evita passar dos 0,5 GB gratuitos.

**3. Sincronização diária (Cloud Scheduler).**

```bash
gcloud scheduler jobs create http sincronizacao-diaria --location us-east1 \
  --schedule "0 6 * * *" --time-zone "America/Sao_Paulo" \
  --uri "https://URL-DO-CLOUD-RUN/api/interno/sincronizacao-diaria" --http-method POST \
  --headers "X-Cron-Token=O_MESMO_CRON_TOKEN" --attempt-deadline 1800s
```

Pra testar na hora: `gcloud scheduler jobs run sincronizacao-diaria --location us-east1`, e veja
os logs em **Cloud Run → almoxarifado-api → Registros**.

## Deploy (Railway)

Num servidor sempre ligado como o Railway, os jobs das 6h rodam dentro da própria API (não precisa
de `AGENDADOR_INTERNO` nem `CRON_TOKEN`).

1. Crie uma conta em [railway.app](https://railway.app) (dá para logar com a conta do GitHub).
2. **New Project → Deploy from GitHub repo** e escolha `almoxarifado-api`. O Railway detecta o
   `Dockerfile` sozinho e builda a partir dele.
3. **New → Database → Add MySQL** no mesmo projeto. O Railway cria as variáveis `MYSQLHOST`,
   `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD` automaticamente.
4. No serviço da API, aba **Variables**, defina:
   - `DB_URL` = `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}`
   - `DB_USERNAME` = `${{MySQL.MYSQLUSER}}`
   - `DB_PASSWORD` = `${{MySQL.MYSQLPASSWORD}}`
   - `CORS_ALLOWED_ORIGINS` = `https://brunotaveiradasilva.github.io`
   - `JWT_SECRET` = um valor aleatório forte (gere com `openssl rand -base64 48`, por exemplo)
   - `ADMIN_USERNAME` e `ADMIN_PASSWORD` = o login que você vai usar pra entrar no app
   - (o Railway já define `PORT` sozinho — não precisa mexer)
5. Gere um domínio público em **Settings → Networking → Generate Domain**. Essa URL
   (`https://algo.up.railway.app`) é o `VITE_API_URL` que o front-end vai usar.

Render funciona de forma parecida: **New → Web Service** apontando pro `Dockerfile`, e
**New → PostgreSQL/MySQL** para o banco (no plano free do Render o MySQL gerenciado não está
disponível — nesse caso, um banco MySQL gratuito externo como o do
[Railway](https://railway.app) ou [Aiven](https://aiven.io) resolve).

## Próximos passos possíveis

- Trocar `ddl-auto: update` por migrations versionadas (Flyway), quando o schema começar a mudar
  bastante. Sem isso, `ddl-auto: update` só adiciona colunas/tabelas, nunca remove: se você já
  tinha rodado a API com a versão antiga de `Representante` (com o campo `codigo`) ou com a tabela
  `tipos_meta`, apague a coluna `codigo` de `representantes` e a tabela `tipos_meta` manualmente antes
  de subir esta versão.
- Endpoint de logout/revogação — hoje um token vale até expirar (`JWT_VALIDADE_HORAS`), não tem
  como invalidar um antes da hora.
- Permitir escolher o `role` do login ao criar outro usuário pela tela (hoje todo login criado
  pela tela/API nasce `USUARIO`; promover a `ADMIN` só é possível direto no banco).
