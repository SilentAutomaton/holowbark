# Holowbark

[English](README.en.md)

Android-приложение для подключения к Wireguard VPN через Yggdrasil без root-прав. Объединяет оверлейную сеть [Yggdrasil](https://yggdrasil-network.github.io/) и [WireGuard](https://www.wireguard.com/) в едином userspace-сервисе.

> **Always-On VPN**: в этом режиме приложение не работает из-за особенностей реализации. Убедитесь, что он отключён.

Готовые APK — в разделе [Releases](https://github.com/SilentAutomaton/holowbark/releases).

## Сборка

### Зависимости

| Инструмент | Версия |
|---|---|
| Go | 1.21+ |
| gomobile | latest (`go install golang.org/x/mobile/cmd/gomobile@latest`) |
| Android SDK | platform-35, build-tools-35 |
| Android NDK | r27 (`ndk;27.2.12479018`) |
| Java | 17+ |

### Быстрый старт

```bash
# Первоначальная настройка: SDK-компоненты, gomobile, клонирование Go-репозиториев
make setup

# Сборка Go AAR + debug APK
make all

# Установка на подключённое устройство
make install
```

### Отдельные цели

```bash
make aar            # сборка holowbark.aar (Yggdrasil + AmneziaWG через gomobile)
make apk            # debug APK (требует aar)
make apk-release    # unsigned release APK
make install        # adb install debug APK
make rebuild        # clean-aar + all (полная пересборка с нуля)
```

Если `app/libs/holowbark.aar` уже собран, можно использовать Gradle напрямую:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Go-библиотека (`holowbark.aar`)

AAR объединяет Yggdrasil и AmneziaWG и **не хранится** в репозитории — его нужно собрать один раз командой `make aar`. Точка входа для gomobile — `contrib/awgmobile/awgmobile.go`; `make clone-deps` копирует её в дерево yggdrasil-go и прописывает нужные Go-зависимости.

## Возможности

- Импорт `.conf`-файла (WireGuard; параметры обфускации AmneziaWG поддерживаются, но не тестировались)
- Просмотр публичных пиров Yggdrasil по странам, выбор и подключение
- Доступ к ресурсам сети Yggdrasil, в том числе к публичным DNS-серверам

---

## Настройка сервера — вручную

WireGuard exit-нода, доступная **только через оверлей Yggdrasil**: UDP-порт WireGuard закрыт от публичного интернета, единственная точка входа — Yggdrasil-адрес сервера.

### 1. Установка Yggdrasil

Пакеты для всех платформ — на [yggdrasil-network.github.io/installation](https://yggdrasil-network.github.io/installation.html). Для Debian/Ubuntu:

```bash
curl -o /etc/apt/trusted.gpg.d/yggdrasil.gpg \
  https://neilalexander.s3.eu-west-2.amazonaws.com/deb/key.gpg
echo "deb https://neilalexander.s3.eu-west-2.amazonaws.com/deb/ debian yggdrasil" \
  > /etc/apt/sources.list.d/yggdrasil.list
apt update && apt install yggdrasil
```

Сгенерировать конфиг и запустить:

```bash
yggdrasil -genconf > /etc/yggdrasil/yggdrasil.conf
systemctl enable --now yggdrasil
```

Добавить несколько публичных пиров в массив `Peers` в `/etc/yggdrasil/yggdrasil.conf` (список: [publicpeers.neilalexander.dev](https://publicpeers.neilalexander.dev/)). Узнать Yggdrasil-адрес сервера:

```bash
yggdrasilctl getSelf | grep '"address"'
# "address": "200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx"
```

### 2. Установка WireGuard

```bash
apt install wireguard-tools
```

Включить IP-форвардинг:

```bash
echo "net.ipv4.ip_forward=1"          >> /etc/sysctl.d/99-forward.conf
echo "net.ipv6.conf.all.forwarding=1" >> /etc/sysctl.d/99-forward.conf
sysctl -p /etc/sysctl.d/99-forward.conf
```

### 3. Генерация ключей

```bash
SERVER_PRIV=$(wg genkey)
SERVER_PUB=$(echo "$SERVER_PRIV" | wg pubkey)
CLIENT_PRIV=$(wg genkey)
CLIENT_PUB=$(echo "$CLIENT_PRIV" | wg pubkey)
echo "Server pub: $SERVER_PUB"
echo "Client pub: $CLIENT_PUB"
```

### 4. Конфиг сервера

Имя внешнего интерфейса: `ip route | awk '/^default/ {print $5}'`

Создать `/etc/wireguard/wg0.conf`:

```ini
[Interface]
Address    = 10.100.0.1/24
ListenPort = 51820
PrivateKey = <SERVER_PRIV>

# NAT-маскарадинг; заменить eth0 на свой внешний интерфейс
PostUp  = iptables -t nat -A POSTROUTING -s 10.100.0.0/24 -o eth0 -j MASQUERADE
PreDown = iptables -t nat -D POSTROUTING -s 10.100.0.0/24 -o eth0 -j MASQUERADE

# Закрыть порт от интернета: IPv4 — полностью, IPv6 — только не из Yggdrasil
PostUp  = iptables  -I INPUT -p udp --dport 51820 -j DROP; \
          ip6tables -I INPUT -p udp --dport 51820 ! -s 200::/7 -j DROP
PreDown = iptables  -D INPUT -p udp --dport 51820 -j DROP; \
          ip6tables -D INPUT -p udp --dport 51820 ! -s 200::/7 -j DROP

[Peer]
PublicKey  = <CLIENT_PUB>
AllowedIPs = 10.100.0.2/32
```

Запустить:

```bash
systemctl enable --now wg-quick@wg0
```

Проверить файрвол с внешней машины:

```bash
# должен молча дропаться
nc -u -z -w2 <внешний-IPv4-сервера> 51820; echo $?

# должен быть доступен изнутри Yggdrasil
nc -u -z -w2 200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx 51820; echo $?
```

### 5. Клиентский `.conf` для Holowbark

```ini
[Interface]
PrivateKey = <CLIENT_PRIV>
Address    = 10.100.0.2/24
DNS        = 1.1.1.1

[Peer]
PublicKey           = <SERVER_PUB>
Endpoint            = [200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx]:51820
AllowedIPs          = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
```

В поле `Endpoint` — Yggdrasil-адрес сервера из шага 1. Файл импортируется через вкладку WG в приложении. После подключения Holowbark сначала поднимает оверлей Yggdrasil, а затем направляет весь трафик через WireGuard-туннель поверх него.

---

## Настройка сервера через wg-easy (Docker)

[wg-easy](https://github.com/wg-easy/wg-easy) — веб-интерфейс для управления WireGuard.

Для установки следуйте [официальному руководству](https://wg-easy.github.io/wg-easy/latest/getting-started/). Единственное отличие для Holowbark: в переменной `WG_HOST` нужно указать **Yggdrasil-адрес сервера**, а не публичный IP:

```
WG_HOST=[200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx]
```

Тогда все генерируемые клиентские конфиги автоматически получат правильный `Endpoint`. Скачайте `.conf` и импортируйте его в Holowbark через вкладку WG.

После первоначальной настройки закройте оба порта от публичного интернета:

```bash
# WireGuard: IPv4 — полностью, IPv6 — только не из Yggdrasil
iptables  -I INPUT -p udp --dport 51820 -j DROP
ip6tables -I INPUT -p udp --dport 51820 ! -s 200::/7 -j DROP

# Веб-интерфейс: аналогично
iptables  -I INPUT -p tcp --dport 51821 -j DROP
ip6tables -I INPUT -p tcp --dport 51821 ! -s 200::/7 -j DROP

# Сохранить правила
apt install iptables-persistent -y
netfilter-persistent save
```

Веб-интерфейс после этого доступен изнутри Yggdrasil по адресу `http://[200:xxxx:...]:51821` или через SSH-туннель:

```bash
ssh -L 51821:localhost:51821 user@<сервер>
# затем открыть http://localhost:51821
```

---

## Маршрутизация пакетов

| Назначение | Путь |
|---|---|
| `200::/7` (оверлей Yggdrasil) | YggdrasilManager → overlay |
| Всё остальное | AwgManager → WireGuard tunnel |

Адреса пиров Yggdrasil определяются при запуске VPN и исключаются из туннельных маршрутов — трафик к ним идёт в физическую сеть напрямую. WireGuard работает через `chanBind`: реальный UDP-сокет не открывается, исходящие пакеты оборачиваются в IPv6/UDP-фреймы и уходят через Yggdrasil, а входящие фреймы разворачиваются и возвращаются в сетевой стек.
