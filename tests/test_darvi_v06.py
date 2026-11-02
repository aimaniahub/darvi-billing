import asyncio, sys, base64
from pathlib import Path
from playwright.async_api import async_playwright
async def load_local(page):
 src=Path('/mnt/data/darvi-billing-prototype')
 html=(src/'index.html').read_text().replace('<script src="legacy-vault.js" defer></script>','').replace('<script src="app.js" defer></script>','')
 html=html.replace('<link rel="stylesheet" href="styles.css" />','<style>'+(src/'styles.css').read_text()+'</style>')
 logo='data:image/jpeg;base64,'+base64.b64encode((src/'assets/Official Darvi Group Logo.jpeg').read_bytes()).decode()
 html=html.replace('assets/Official Darvi Group Logo.jpeg',logo)
 await page.set_content(html)
 await page.evaluate("""() => {let m={};let s={};Object.defineProperty(window,'localStorage',{configurable:true,value:{getItem:k=>m[k]??null,setItem:(k,v)=>m[k]=String(v),removeItem:k=>delete m[k]}});Object.defineProperty(window,'sessionStorage',{configurable:true,value:{getItem:k=>s[k]??null,setItem:(k,v)=>s[k]=String(v),removeItem:k=>delete s[k]}});}""")
 await page.add_script_tag(content=(src/'legacy-vault.js').read_text())
 await page.add_script_tag(content=(src/'app.js').read_text())
async def run():
 async with async_playwright() as p:
  b=await p.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox'])
  page=await b.new_page(viewport={'width':1400,'height':930},accept_downloads=True)
  errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  page.on('console',lambda m:errors.append('console: '+m.text) if m.type=='error' else None)
  await load_local(page)
  await page.evaluate("window.print=()=>{window.__lastPrint={format:document.body.dataset.printFormat,html:document.querySelector('#print-sheet').innerHTML}}")
  await page.locator('#login-form input[name=pin]').fill('1234');await page.locator('#login-form button[type=submit]').click()
  assert await page.locator('#home-phone-search').count()==1
  assert await page.locator('[data-nav=leads]').count()>0
  await page.locator('[data-nav=leads]').first.click()
  await page.locator('[data-action=crm-new-lead]').first.click()
  await page.locator('#crm-lead-form input[name=name]').fill('Lead Demo Six')
  await page.locator('#crm-lead-form input[name=phone]').fill('+91 98765 43210')
  await page.locator('#crm-lead-form input[name=email]').fill('demo6@example.org')
  await page.locator('#crm-lead-form input[name=interest]').fill('Mexican Hass Avocado')
  await page.locator('#crm-lead-form input[name=followUpAt]').fill('2026-11-02')
  await page.locator('#crm-lead-form select[name=assignedTo]').select_option('staff-anurag')
  await page.locator('#crm-lead-form button[type=submit]').click()
  d=await page.evaluate("JSON.parse(localStorage.getItem('darvi_billing_v02')).businesses['darvi-group']")
  assert len(d['leads'])==1 and d['leads'][0]['name']=='Lead Demo Six',d['leads']
  assert d['leads'][0]['assignedTo']=='staff-anurag'
  await page.locator('[data-action=crm-view-lead]').first.click()
  await page.locator('#crm-note-form textarea[name=note]').fill('Called and arranged nursery visit')
  await page.locator('#crm-note-form button[type=submit]').click()
  assert 'Called and arranged nursery visit' in await page.locator('#modal-layer').inner_text()
  await page.locator('[data-action=crm-convert-customer]').click()
  d=await page.evaluate("JSON.parse(localStorage.getItem('darvi_billing_v02')).businesses['darvi-group']")
  customer=next(c for c in d['customers'] if c['name']=='Lead Demo Six')
  assert customer['customerCode'].startswith(('DG-C','DB-C'))
  assert d['leads'][0]['stage']=='Won'
  assert d['leads'][0]['customerId']==customer['id']
  await page.locator('[data-nav=dashboard]').first.click()
  await page.locator('#home-phone-search').fill('43210')
  txt=await page.locator('#home-phone-results').inner_text()
  assert 'Lead Demo Six' in txt,txt
  await page.locator('[data-action=crm-customer-history]').first.click()
  assert customer['customerCode'] in await page.locator('#modal-layer').inner_text()
  await page.locator('[data-action=close-modal]').first.click()
  await page.locator('[data-nav=new]').first.click()
  await page.locator('[data-draft=customerId]').select_option(customer['id'])
  assert await page.locator('[data-draft=shippingName]').input_value()=='Lead Demo Six'
  await page.locator('[data-draft=shippingAddress]').fill('Door No 12, Green Lane, Hubli')
  await page.locator('[data-draft=shippingCity]').fill('Hubballi')
  await page.locator('[data-draft=shippingState]').fill('Karnataka')
  await page.locator('[data-draft=shippingPincode]').fill('580020')
  await page.locator('#product-picker').select_option('p1');await page.locator('[data-action=add-line]').click()
  await page.locator('[data-action=save-invoice]').click()
  d=await page.evaluate("JSON.parse(localStorage.getItem('darvi_billing_v02')).businesses['darvi-group']")
  assert len(d['invoices'])==1 and d['invoices'][0]['customerInvoiceSequence']==1
  assert d['invoices'][0]['customerInvoiceRef'].startswith(customer['customerCode'])
  assert d['invoices'][0]['shippingSnapshot']['pincode']=='580020',d['invoices'][0]['shippingSnapshot']
  await page.locator('[data-action=print-shipping-4x6]').click()
  label=await page.evaluate('window.__lastPrint')
  assert label['format']=='shipping4x6' and 'Green Lane' in label['html'] and d['invoices'][0]['number'] in label['html']
  await page.emulate_media(media='print')
  await page.screenshot(path='/mnt/data/Darvi_Billing_v0.6_Shipping_Label.png',full_page=True)
  await page.emulate_media(media='screen')
  await page.locator('[data-action=print-shipping-a4]').click()
  label=await page.evaluate('window.__lastPrint')
  assert label['format']=='shippingA4' and label['html'].count('class="shipping-label"')==4
  await page.locator('[data-nav=new]').first.click()
  await page.locator('[data-draft=customerId]').select_option(customer['id'])
  await page.locator('#product-picker').select_option('p1');await page.locator('[data-action=add-line]').click()
  await page.locator('[data-action=save-invoice]').click()
  d=await page.evaluate("JSON.parse(localStorage.getItem('darvi_billing_v02')).businesses['darvi-group']")
  assert d['invoices'][0]['number']!=d['invoices'][1]['number']
  assert d['invoices'][1]['customerInvoiceSequence']==2 and d['invoices'][1]['customerInvoiceRef']!=d['invoices'][0]['customerInvoiceRef']
  await page.screenshot(path='/mnt/data/Darvi_Billing_v0.6_Invoice_Shipping.png',full_page=True)
  # staff may add leads, but not customer master
  await page.locator('[data-action=signout]').click()
  await page.locator('#login-form select[name=userId]').select_option('staff-anurag')
  await page.locator('#login-form input[name=pin]').fill('1234');await page.locator('#login-form button[type=submit]').click()
  await page.locator('[data-nav=leads]').first.click()
  assert 'Lead Demo Six' in await page.locator('#main-content').inner_text() or '0' in await page.locator('#main-content').inner_text()
  await page.locator('[data-action=crm-new-lead]').first.click()
  await page.locator('#crm-lead-form input[name=name]').fill('Staff Lead Test')
  await page.locator('#crm-lead-form input[name=phone]').fill('9999912345')
  assert await page.locator('#crm-lead-form select[name=assignedTo] option').count()==1
  await page.locator('#crm-lead-form button[type=submit]').click()
  d=await page.evaluate("JSON.parse(localStorage.getItem('darvi_billing_v02')).businesses['darvi-group']")
  assert len(d['leads'])==2 and d['leads'][1]['createdBy']=='staff-anurag'
  await page.locator('#crm-lead-filter').select_option('all')
  await page.evaluate("document.querySelector('#toast-root').style.display='none';document.querySelector('.sidebar').classList.remove('open')")
  await page.screenshot(path='/mnt/data/Darvi_Billing_v0.6_CRM_Desktop.png',full_page=True)
  await page.set_viewport_size({'width':390,'height':844})
  await page.wait_for_timeout(200)
  await page.locator('.sidebar').evaluate("e=>e.classList.remove('open')")
  await page.screenshot(path='/mnt/data/Darvi_Billing_v0.6_CRM_Mobile.png',full_page=True)
  await page.set_viewport_size({'width':1400,'height':930})
  assert await page.locator('[data-action=crm-convert-customer]').count()==0
  assert await page.locator('[data-nav=customers]').count()==0
  await page.locator('[data-nav=dashboard]').first.click()
  await page.locator('#home-phone-search').fill('43210')
  assert 'Lead Demo Six' in await page.locator('#home-phone-results').inner_text()
  await page.set_viewport_size({'width':390,'height':844})
  await page.wait_for_timeout(200)
  await page.locator('.sidebar').evaluate("e=>e.classList.remove('open')")
  await page.screenshot(path='/mnt/data/Darvi_Billing_v0.6_Phone_Search.png',full_page=True)
  assert not errors,errors
  print('V0.6 PASS crm, lead follow-up, conversion, staff permissions, phone search, customer IDs, invoice refs, shipping 4x6 & A4',len(d['leads']),len(d['invoices']))
  await b.close()
asyncio.run(run())
