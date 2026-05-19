import os
import json
import threading
import time
import base64
from datetime import datetime
from io import BytesIO

from kivy.app import App
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.uix.popup import Popup
from kivy.uix.label import Label as KivyLabel
from kivy.uix.boxlayout import BoxLayout
from kivy.clock import Clock
from kivy.lang import Builder
from kivy.core.image import Image as CoreImage
from kivy.core.text import LabelBase
from kivy.properties import StringProperty, BooleanProperty, ObjectProperty

import qrcode
from PIL import Image as PILImage

from lib import backend

_font_path = None
for _fp in [
    'C:/Windows/Fonts/msyh.ttc',
    'C:/Windows/Fonts/msyh.ttf',
    'C:/Windows/Fonts/simhei.ttf',
    os.path.join(os.path.dirname(__file__), 'lib', 'NotoSansSC-Regular.otf'),
]:
    if os.path.exists(_fp):
        _font_path = _fp
        LabelBase.register(name='CJK', fn_regular=_fp)
        break

def show_popup(title, message, on_dismiss=None):
    content = BoxLayout(orientation='vertical', padding='16dp', spacing='12dp')
    content.add_widget(KivyLabel(text=message, text_size=(None, None), halign='center', valign='middle'))
    btn_layout = BoxLayout(size_hint_y=None, height='48dp', spacing='12dp')
    from kivy.uix.button import Button
    close_btn = Button(text='确定', size_hint_x=1)
    btn_layout.add_widget(close_btn)
    content.add_widget(btn_layout)
    popup = Popup(title=title, content=content, size_hint=(0.75, 0.35), auto_dismiss=False)
    close_btn.bind(on_release=popup.dismiss)
    if on_dismiss:
        popup.bind(on_dismiss=on_dismiss)
    popup.open()

KV = """
#:import C kivy.utils.get_color_from_hex

<Label>:
    font_name: 'CJK' if app.font_ok else ''
<Button>:
    font_name: 'CJK' if app.font_ok else ''
<TextInput>:
    font_name: 'CJK' if app.font_ok else ''

<LoginScreen>:
    BoxLayout:
        orientation: 'vertical'
        spacing: '8dp'
        padding: '24dp'

        Label:
            text: 'AutoTicket 登录'
            font_size: '28sp'
            bold: True
            size_hint_y: None
            height: '60dp'
            color: C('#1d1d1f')

        BoxLayout:
            size_hint_y: None
            height: '44dp'
            spacing: '12dp'
            Button:
                text: '密码登录'
                background_color: C('#007aff') if root.login_mode == 'password' else C('#f5f5f7')
                color: C('#ffffff') if root.login_mode == 'password' else C('#6e6e73')
                on_release: root.login_mode = 'password'
            Button:
                text: '短信验证码登录'
                background_color: C('#007aff') if root.login_mode == 'sms' else C('#f5f5f7')
                color: C('#ffffff') if root.login_mode == 'sms' else C('#6e6e73')
                on_release: root.switch_to_sms()

        TextInput:
            id: phone
            hint_text: '手机号'
            size_hint_y: None
            height: '48dp'
            input_filter: 'int'
            multiline: False

        TextInput:
            id: password
            hint_text: '密码'
            size_hint_y: None
            height: '48dp'
            password: True
            multiline: False
            disabled: root.login_mode == 'sms'
            opacity: 1 if root.login_mode == 'password' else 0

        BoxLayout:
            size_hint_y: None
            height: '120dp'
            spacing: '12dp'
            Image:
                id: captcha_img
                size_hint_x: 0.5
                allow_stretch: True
                keep_ratio: True
            Button:
                text: '刷新验证码' if not root.loading_captcha else '获取中...'
                size_hint_x: 0.5
                on_release: root.refresh_captcha()

        TextInput:
            id: captcha_code
            hint_text: '图形验证码'
            size_hint_y: None
            height: '48dp'
            multiline: False

        BoxLayout:
            size_hint_y: None
            height: '48dp'
            spacing: '12dp'
            disabled: root.login_mode == 'password'
            opacity: 1 if root.login_mode == 'sms' else 0
            TextInput:
                id: sms_code
                hint_text: '短信验证码'
                multiline: False
            Button:
                id: sms_btn
                text: '获取验证码'
                size_hint_x: 0.4
                on_release: root.send_sms_code()

        BoxLayout:
            size_hint_y: None
            height: '52dp'
            spacing: '12dp'
            Button:
                text: '返回'
                size_hint_x: 0.3
                on_release: root.go_back()
            Button:
                text: '登录'
                size_hint_x: 0.7
                background_color: C('#007aff')
                color: C('#ffffff')
                on_release: root.do_login()

<MainScreen>:
    BoxLayout:
        orientation: 'vertical'
        spacing: '8dp'
        padding: '16dp'

        BoxLayout:
            size_hint_y: None
            height: '44dp'
            spacing: '12dp'
            Label:
                text: '登录状态:'
                size_hint_x: 0.35
            Label:
                id: status_label
                text: root.login_status
                bold: True
                color: C('#1f9d55') if root.login_status == '已登录' else C('#d93025')
            Button:
                id: login_btn
                text: '登录' if root.login_status == '未登录' else '退出登录'
                size_hint_x: 0.3
                on_release: root.on_login_toggle()

        ScrollView:
            GridLayout:
                cols: 2
                spacing: '8dp'
                size_hint_y: None
                height: self.minimum_height
                row_default_height: '44dp'
                Label:
                    text: 'LOGIN_NAME / USER_ID'
                TextInput:
                    id: login_name
                    multiline: False
                Label:
                    text: 'SES_ID'
                TextInput:
                    id: ses_id
                    multiline: False
                Label:
                    text: 'EXCHANGE_ID (9/10/11)'
                TextInput:
                    id: exchange_id
                    text: '10'
                    multiline: False
                Label:
                    text: '抢票时间'
                TextInput:
                    id: run_time
                    multiline: False
                Label:
                    text: '运行次数'
                TextInput:
                    id: run_count
                    text: '10'
                    multiline: False
                Label:
                    text: '运行间隔(秒)'
                TextInput:
                    id: time_sleep
                    text: '0.5'
                    multiline: False

        BoxLayout:
            size_hint_y: None
            height: '48dp'
            spacing: '8dp'
            Button:
                id: start_btn
                text: '启动兑换'
                on_release: root.start_exchange()
            Button:
                id: stop_btn
                text: '取消'
                disabled: True
                on_release: root.stop_exchange()

        BoxLayout:
            size_hint_y: None
            height: '48dp'
            spacing: '8dp'
            Button:
                id: daily_btn
                text: '每日任务'
                on_release: root.run_daily_task()

        BoxLayout:
            size_hint_y: None
            height: '48dp'
            spacing: '8dp'
            Button:
                text: '绿色出行码'
                on_release: root.show_qr_code()

        Label:
            id: log_label
            text: '运行日志'
            size_hint_y: None
            height: '26dp'
            bold: True

        TextInput:
            id: log_area
            readonly: True
            text: ''
            size_hint_y: 0.3

<QRCodeScreen>:
    BoxLayout:
        orientation: 'vertical'
        spacing: '8dp'
        padding: '16dp'

        FloatLayout:
            size_hint_y: 0.65
            Image:
                id: qr_image
                size_hint: (0.85, 0.85)
                pos_hint: {'center_x': 0.5, 'center_y': 0.5}
                allow_stretch: True
                keep_ratio: True

        Label:
            id: money_label
            text: '加载中...'
            font_size: '24sp'
            bold: True
            halign: 'center'
            size_hint_y: None
            height: self.texture_size[1]

        Label:
            id: info_label
            text: ''
            halign: 'center'
            color: C('#6e6e73')
            size_hint_y: None
            height: self.texture_size[1]

        Widget:

        Button:
            text: '关闭'
            size_hint_y: None
            height: '48dp'
            on_release: root.back_to_main()
"""

class LoginScreen(Screen):
    login_mode = StringProperty('password')
    loading_captcha = BooleanProperty(False)
    captcha_data = ObjectProperty(None)
    sms_countdown = 0

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._sms_timer = None
        self._sms_clock_event = None

    def on_enter(self):
        self.refresh_captcha()

    def refresh_captcha(self):
        self.loading_captcha = True
        threading.Thread(target=self._fetch_captcha, daemon=True).start()

    def _fetch_captcha(self):
        try:
            data = backend.get_captcha()
            Clock.schedule_once(lambda dt: self._on_captcha_ok(data))
        except Exception as e:
            Clock.schedule_once(lambda dt: show_popup('错误', f'获取验证码失败: {e}'))
        finally:
            Clock.schedule_once(lambda dt: setattr(self, 'loading_captcha', False))

    def _on_captcha_ok(self, data):
        self.captcha_data = data
        raw_img = data.get('img', '')
        if ',' in raw_img:
            raw_img = raw_img.split(',', 1)[1]
        try:
            cleaned = raw_img.replace('\\', '').replace('\n', '').replace('\r', '').replace(' ', '')
            img_bytes = base64.b64decode(cleaned)
            pil_img = PILImage.open(BytesIO(img_bytes))
            buf = BytesIO()
            pil_img.save(buf, format='PNG')
            buf.seek(0)
            core_img = CoreImage(buf, ext='png')
            self.ids.captcha_img.texture = core_img.texture
        except Exception as e:
            show_popup('错误', f'验证码图片解析失败: {e}')

    def go_back(self):
        self.manager.current = 'main'

    def switch_to_sms(self):
        self.login_mode = 'sms'

    def send_sms_code(self):
        if not self.captcha_data:
            show_popup('提示', '请先刷新验证码')
            return
        phone = self.ids.phone.text.strip()
        captcha = self.ids.captcha_code.text.strip()
        if not phone or not captcha:
            show_popup('提示', '请填写手机号和图形验证码')
            return
        self.ids.sms_btn.disabled = True
        threading.Thread(target=self._do_send_sms, args=(phone, captcha), daemon=True).start()

    def _do_send_sms(self, phone, captcha):
        try:
            result = backend.send_sms(self.captcha_data, phone, captcha)
            if result.get('result') == '0':
                Clock.schedule_once(lambda dt: self._start_sms_countdown())
            else:
                msg = result.get('msg', '发送失败')
                Clock.schedule_once(lambda dt: show_popup('发送失败', msg))
        except Exception as e:
            Clock.schedule_once(lambda dt: show_popup('错误', f'发送失败: {e}'))
        finally:
            Clock.schedule_once(lambda dt: self.ids.sms_btn.__setattr__('disabled', False))

    def _start_sms_countdown(self):
        self.sms_countdown = 60
        self.ids.sms_btn.disabled = True
        if self._sms_clock_event:
            self._sms_clock_event.cancel()
        self._sms_clock_event = Clock.schedule_interval(self._tick_sms, 1)

    def _tick_sms(self, dt):
        self.sms_countdown -= 1
        if self.sms_countdown <= 0:
            self.ids.sms_btn.text = '获取验证码'
            self.ids.sms_btn.disabled = False
            if self._sms_clock_event:
                self._sms_clock_event.cancel()
                self._sms_clock_event = None
            return False
        self.ids.sms_btn.text = f'{self.sms_countdown}s'

    def do_login(self):
        phone = self.ids.phone.text.strip()
        captcha_code = self.ids.captcha_code.text.strip()
        if not phone or not captcha_code or not self.captcha_data:
            show_popup('提示', '请填写手机号和验证码')
            return
        if self.login_mode == 'sms':
            sms_code = self.ids.sms_code.text.strip()
            if not sms_code:
                show_popup('提示', '请填写短信验证码')
                return
            threading.Thread(target=self._do_login, args=('sms', phone, None, captcha_code, sms_code), daemon=True).start()
        else:
            pwd = self.ids.password.text
            if not pwd:
                show_popup('提示', '请填写密码')
                return
            threading.Thread(target=self._do_login, args=('password', phone, pwd, captcha_code, None), daemon=True).start()

    def _do_login(self, mode, phone, password, captcha_code, sms_code):
        try:
            if mode == 'sms':
                result = backend.login_u065(phone, sms_code)
            else:
                result = backend.login_u004_with_code(self.captcha_data, phone, password, captcha_code)
            if result.get('result') == '0':
                login_name = result.get('login_name') or result.get('user_id') or phone
                ses_id = result.get('ses_id', '')
                user_id = result.get('user_id') or login_name
                Clock.schedule_once(lambda dt, ln=login_name, ui=user_id, si=ses_id: self._login_success(ln, ui, si))
            else:
                msg = result.get('msg', '登录失败')
                Clock.schedule_once(lambda dt: show_popup('登录失败', msg))
        except Exception as e:
            Clock.schedule_once(lambda dt: show_popup('错误', f'登录失败: {e}'))

    def _login_success(self, login_name, user_id, ses_id):
        app = App.get_running_app()
        app.login_name = login_name
        app.user_id = user_id
        app.ses_id = ses_id
        app.save_auth()
        main_screen = self.manager.get_screen('main')
        main_screen.load_config()
        self.manager.current = 'main'

class MainScreen(Screen):
    login_status = StringProperty('未登录')
    _exchange_running = False

    def on_enter(self):
        self.load_config()

    def load_config(self):
        app = App.get_running_app()
        if app.login_name:
            self.login_status = '已登录'
            self.ids.login_name.text = app.login_name
            self.ids.ses_id.text = app.ses_id
            self.ids.login_name.text = app.login_name
        cfg = app.load_config()
        if cfg.get('exchange_id'):
            self.ids.exchange_id.text = cfg['exchange_id']
        if cfg.get('run_count'):
            self.ids.run_count.text = cfg['run_count']
        if cfg.get('time_sleep'):
            self.ids.time_sleep.text = cfg['time_sleep']
        if not self.ids.run_time.text:
            self.ids.run_time.text = self._next_run_time()

    def _next_run_time(self):
        now = datetime.now()
        today = now.date()
        t0700 = datetime.combine(today, datetime.strptime('07:00', '%H:%M').time())
        t1130 = datetime.combine(today, datetime.strptime('11:30', '%H:%M').time())
        t1700 = datetime.combine(today, datetime.strptime('17:00', '%H:%M').time())
        t0700_next = datetime.combine(today, datetime.strptime('07:00', '%H:%M').time()).replace(day=today.day + 1)
        if now < t0700:
            target = t0700
        elif now < t1130:
            target = t1130
        elif now < t1700:
            target = t1700
        else:
            target = t0700_next
        return target.strftime('%Y-%m-%d %H:%M:%S')

    def on_login_toggle(self):
        if self.login_status == '未登录':
            self.manager.current = 'login'
        else:
            self.do_logout()

    def do_logout(self):
        app = App.get_running_app()
        app.login_name = ''
        app.user_id = ''
        app.ses_id = ''
        app.clear_auth()
        self.login_status = '未登录'
        self.ids.login_name.text = ''
        self.ids.ses_id.text = ''
        self.manager.current = 'login'

    def log(self, msg):
        Clock.schedule_once(lambda dt: self._append_log(msg))

    def _append_log(self, msg):
        area = self.ids.log_area
        ts = datetime.now().strftime('%H:%M:%S')
        area.text += f'[{ts}] {msg}\n'
        area.cursor = (0, len(area.text))

    def start_exchange(self):
        app = App.get_running_app()
        login_name = self.ids.login_name.text.strip()
        ses_id = self.ids.ses_id.text.strip()
        exchange_id = self.ids.exchange_id.text.strip()
        run_time_str = self.ids.run_time.text.strip()
        run_count = self.ids.run_count.text.strip()
        time_sleep = self.ids.time_sleep.text.strip()
        if not all([login_name, ses_id, exchange_id, run_time_str, run_count, time_sleep]):
            self.log('错误: 所有字段都必须填写')
            return
        try:
            run_time = datetime.strptime(run_time_str, '%Y-%m-%d %H:%M:%S')
            run_count_int = int(run_count)
            time_sleep_f = float(time_sleep)
        except ValueError:
            self.log('错误: 时间格式或数字格式不正确')
            return
        app.save_full_config({
            'exchange_id': exchange_id,
            'run_count': run_count,
            'time_sleep': time_sleep
        })
        self._exchange_running = True
        self.ids.start_btn.text = '运行中...'
        self.ids.start_btn.disabled = True
        self.ids.stop_btn.disabled = False
        threading.Thread(
            target=self._run_exchange,
            args=(login_name, app.user_id or login_name, ses_id, exchange_id, run_time, run_count_int, time_sleep_f),
            daemon=True
        ).start()

    def _run_exchange(self, login_name, user_id, ses_id, exchange_id, run_time, run_count, time_sleep):
        self.log(f'等待目标时间: {run_time}')
        while True:
            now = datetime.now()
            if now >= run_time:
                break
            diff = (run_time - now).total_seconds()
            if diff > 60:
                time.sleep(min(30, diff))
            elif diff > 1:
                time.sleep(min(0.5, diff))
            else:
                time.sleep(min(0.05, diff))
        if not self._exchange_running:
            Clock.schedule_once(lambda dt: self._exchange_done())
            return
        self.log(f'开始兑换, 共{run_count}次, 间隔{time_sleep}秒')
        for i in range(run_count):
            if not self._exchange_running:
                break
            try:
                result = backend.run_exchange_once(login_name, user_id, ses_id, exchange_id)
                self.log(f'第{i+1}次: {result}')
            except Exception as e:
                self.log(f'第{i+1}次错误: {e}')
            time.sleep(time_sleep)
        self.log('兑换完成')
        Clock.schedule_once(lambda dt: self._exchange_done())

    def stop_exchange(self):
        self._exchange_running = False
        self.log('用户取消')
        Clock.schedule_once(lambda dt: self._exchange_done())

    def _exchange_done(self):
        self._exchange_running = False
        self.ids.start_btn.text = '启动兑换'
        self.ids.start_btn.disabled = False
        self.ids.stop_btn.disabled = True

    def run_daily_task(self):
        app = App.get_running_app()
        login_name = self.ids.login_name.text.strip()
        ses_id = self.ids.ses_id.text.strip()
        if not login_name or not ses_id:
            self.log('错误: 请先登录')
            return
        self.ids.daily_btn.text = '运行中...'
        self.ids.daily_btn.disabled = True
        threading.Thread(
            target=self._do_daily_task,
            args=(login_name, ses_id),
            daemon=True
        ).start()

    def _do_daily_task(self, login_name, ses_id):
        try:
            backend.run_daily_task(login_name, ses_id, self.log)
        except Exception as e:
            self.log(f'每日任务错误: {e}')
        Clock.schedule_once(lambda dt: self._daily_done())

    def _daily_done(self):
        self.ids.daily_btn.text = '每日任务'
        self.ids.daily_btn.disabled = False

    def show_qr_code(self):
        app = App.get_running_app()
        login_name = self.ids.login_name.text.strip()
        ses_id = self.ids.ses_id.text.strip()
        if not login_name or not ses_id:
            self.log('错误: 请先登录')
            return
        self.log('正在获取绿色出行码...')
        threading.Thread(target=self._fetch_qr, args=(login_name, ses_id), daemon=True).start()

    def _fetch_qr(self, user_id, ses_id):
        try:
            token = backend.get_qr_token(user_id, ses_id)
            self.log(f'token 获取成功')
            data = backend.get_qr_code(token)
            Clock.schedule_once(lambda dt: self._show_qr(data, user_id))
        except Exception as e:
            self.log(f'绿色出行码错误: {e}')

    def _show_qr(self, data, user_id):
        qr_screen = self.manager.get_screen('qrcode')
        qr_screen.show_data(data)
        self.manager.current = 'qrcode'
        threading.Thread(target=lambda: backend.record_qr_visit(user_id), daemon=True).start()

class QRCodeScreen(Screen):
    def show_data(self, data):
        qrcode_hex = data.get('qrcode', '')
        money = data.get('money', '0.00')
        card_no = data.get('trafficCardNo', '')
        deadline = data.get('deadTime', '')

        self.ids.money_label.text = f'余额: {money} 元'
        info = f'交通卡号: {card_no}' if card_no else ''
        if deadline:
            try:
                ts = int(deadline)
                dl = datetime.fromtimestamp(ts).strftime('%Y-%m-%d %H:%M:%S')
                info += f'\n有效期至: {dl}' if info else f'有效期至: {dl}'
            except:
                pass
        self.ids.info_label.text = info

        if qrcode_hex:
            threading.Thread(target=self._render_qr, args=(qrcode_hex,), daemon=True).start()

    def _render_qr(self, qrcode_hex):
        try:
            pil_img = qrcode.make(qrcode_hex).convert('RGBA')
            buf = BytesIO()
            pil_img.save(buf, format='PNG')
            buf.seek(0)
            core_img = CoreImage(buf, ext='png')
            Clock.schedule_once(lambda dt: self._set_qr_texture(core_img.texture))
        except Exception as e:
            Clock.schedule_once(lambda dt: self.ids.money_label.__setattr__('text', f'二维码生成失败: {e}'))

    def _set_qr_texture(self, texture):
        self.ids.qr_image.texture = texture

    def back_to_main(self):
        self.manager.current = 'main'

class AutoTicketApp(App):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.login_name = ''
        self.user_id = ''
        self.ses_id = ''
        self.font_ok = _font_path is not None

    def build(self):
        Builder.load_string(KV)
        sm = ScreenManager()
        sm.add_widget(MainScreen(name='main'))
        sm.add_widget(LoginScreen(name='login'))
        sm.add_widget(QRCodeScreen(name='qrcode'))
        self._load_auth()
        return sm

    def _storage_path(self):
        return os.path.join(self.user_data_dir, 'config.json')

    def _auth_path(self):
        return os.path.join(self.user_data_dir, 'auth.json')

    def save_auth(self):
        data = {
            'login_name': self.login_name,
            'user_id': self.user_id,
            'ses_id': self.ses_id
        }
        with open(self._auth_path(), 'w') as f:
            json.dump(data, f)

    def clear_auth(self):
        if os.path.exists(self._auth_path()):
            os.remove(self._auth_path())

    def _load_auth(self):
        path = self._auth_path()
        if os.path.exists(path):
            try:
                with open(path) as f:
                    data = json.load(f)
                self.login_name = data.get('login_name', '')
                self.user_id = data.get('user_id', '')
                self.ses_id = data.get('ses_id', '')
            except:
                pass

    def load_config(self):
        path = self._storage_path()
        if os.path.exists(path):
            try:
                with open(path) as f:
                    return json.load(f)
            except:
                pass
        return {}

    def save_full_config(self, cfg):
        existing = self.load_config()
        existing.update(cfg)
        with open(self._storage_path(), 'w') as f:
            json.dump(existing, f)

if __name__ == '__main__':
    AutoTicketApp().run()
