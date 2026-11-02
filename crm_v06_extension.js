/* Darvi Billing v0.6 - per-business CRM, customer phone lookup, and shipping labels.
   All state in this prototype remains browser-local. Production: server-enforced access & IDs. */
const digits = value=>String(value||'').replace(/\D/g,'');
const phoneKey = value=>{const v=digits(value);return v.length>=10?v.slice(-10):v;};
const clientCode = customer=>customer?.customerCode||customer?.id||'WALK-IN';
const getLead = id=>(db.leads||[]).find(l=>l.id===id);
const userCanSeeLead = l=>!!l && (isAdmin()||l.assignedTo===currentUserId||l.createdBy===currentUserId);
const visibleLeads=()=>(db.leads||[]).filter(userCanSeeLead);
const crmStages=['New','Contacted','Qualified','Quotation Sent','Negotiation','Won','Lost'];
const shippingValue=(draft,key,customer)=>draft?.[key]||customer?.[key]||'';
function initializeCrmData(){
 for(const item of Object.values(store.businesses)){
  item.leads=Array.isArray(item.leads)?item.leads:[];
  item.customerCounter=Number(item.customerCounter)||0;
  const used=new Set(item.customers.map(c=>c.customerCode).filter(Boolean));
  for(const c of item.customers){
   if(c.id==='walk-in'){c.customerCode=c.customerCode||'WALK-IN';continue;}
   if(!c.customerCode){let code;do{code=`${item.business.invoicePrefix||'DB'}-C${String(++item.customerCounter).padStart(5,'0')}`;}while(used.has(code));c.customerCode=code;used.add(code);}
   c.customerInvoiceCounter=Number(c.customerInvoiceCounter)||0;
   c.shippingAddress=c.shippingAddress??c.address??'';
  }
  const sorted=item.invoices.slice().sort((a,b)=>(a.createdAt||a.date||'').localeCompare(b.createdAt||b.date||''));
  const seq=new Map();
  for(const inv of sorted){
   const cid=inv.customerId||'walk-in';const cust=item.customers.find(c=>c.id===cid);
   const next=(seq.get(cid)||0)+1;
   const parsed=Number(inv.customerInvoiceSequence)||Number((inv.customerInvoiceRef||'').match(/-(\d+)$/)?.[1])||0;
   const serial=Math.max(next,parsed);seq.set(cid,serial);
   if(!inv.customerInvoiceSequence)inv.customerInvoiceSequence=serial;
   if(!inv.customerInvoiceRef){inv.customerInvoiceRef=`${cust?.customerCode||'WALK-IN'}-I${String(serial).padStart(4,'0')}`;}
   if(!inv.shippingSnapshot){const c=inv.customerSnapshot||cust||{};inv.shippingSnapshot={name:c.shippingName||c.name||inv.customerName||'',phone:c.shippingPhone||c.phone||'',address:c.shippingAddress||c.address||'',city:c.shippingCity||'',state:c.shippingState||'',pincode:c.shippingPincode||''};}
  }
  for(const c of item.customers)c.customerInvoiceCounter=Math.max(Number(c.customerInvoiceCounter)||0,seq.get(c.id)||0);
 }
}
initializeCrmData();
function homePhoneLookup(){
 const term=digits(ui.homePhone||'');
 const tooShort=term.length>0&&term.length<4;
 const matchesCustomer=term.length>=4?db.customers.filter(c=>c.id!=='walk-in'&&digits(c.phone).includes(term)||c.id!=='walk-in'&&digits(c.shippingPhone).includes(term)).slice(0,14):[];
 const matchesLeads=term.length>=4?visibleLeads().filter(l=>digits(l.phone).includes(term)).slice(0,14):[];
 const matchedIds=new Set(matchesCustomer.map(c=>c.id));
 const matchesInvoices=term.length>=4?db.invoices.filter(i=>(isAdmin()||i.createdBy===currentUserId||i.salespersonId===currentUserId)&&!matchedIds.has(i.customerId)&&digits(i.customerSnapshot?.phone||'').includes(term)).slice(-8).reverse():[];
 return `<section class="phone-lookup card"><div class="card-header"><div><h3>🔎 Find customer by phone</h3><div class="card-sub">Search this business's customer, lead and invoice records using mobile number only.</div></div><button class="button button-soft" data-nav="leads">Open mini CRM</button></div><label class="input-label" for="home-phone-search">Mobile phone number</label><input id="home-phone-search" class="field" type="tel" inputmode="tel" autocomplete="off" placeholder="Enter last 4–10 digits" value="${esc(ui.homePhone||'')}" aria-label="Search by phone number"/><div id="home-phone-results">${tooShort?`<div class="helper-text">Enter at least four digits to search.</div>`:term.length>=4?`<div class="phone-matches">${matchesCustomer.map(c=>`<article class="phone-result"><div><strong>${esc(c.name)}</strong><small>${esc(c.phone)} · ${esc(clientCode(c))}</small></div><button class="button button-small" data-action="crm-customer-history" data-id="${esc(c.id)}">View history</button></article>`).join('')}${matchesLeads.map(l=>`<article class="phone-result"><div><strong>${esc(l.name)}</strong><small>${esc(l.phone)} · Lead · ${esc(l.stage)}</small></div><button class="button button-small" data-action="crm-view-lead" data-id="${esc(l.id)}">Open lead</button></article>`).join('')}${matchesInvoices.map(i=>`<article class="phone-result"><div><strong>${esc(i.customerName||'Invoice contact')}</strong><small>${esc(i.customerSnapshot?.phone||'')} · ${esc(i.number)}</small></div><button class="button button-small" data-action="view-invoice" data-id="${esc(i.id)}">Invoice</button></article>`).join('')}${!matchesCustomer.length&&!matchesLeads.length&&!matchesInvoices.length?'<div class="empty">No matching phone number in this business.</div>':''}</div>`:'<div class="helper-text">Customer information is separated by business. Admins can switch workspaces to search other businesses.</div>'}</div></section>`;
}
function leadDueStatus(l){
 if(!l.followUpAt||['Won','Lost'].includes(l.stage))return 'No upcoming reminder';
 const day=String(l.followUpAt).slice(0,10),today=localDate();
 return day<today?'Overdue':day===today?'Due today':'Upcoming';
}
function leadsPage(){
 if(!can('leads'))return rowEmpty('Access restricted');
 const all=visibleLeads(),filter=ui.leadFilter||'active',term=(ui.leadSearch||'').toLowerCase();
 const selected=all.filter(l=>{
  if(filter==='active'&&['Won','Lost'].includes(l.stage))return false;
  if(filter==='followups'&&(!l.followUpAt||['Won','Lost'].includes(l.stage)))return false;
  if(filter==='overdue'&&leadDueStatus(l)!=='Overdue'&&leadDueStatus(l)!=='Due today')return false;
  if(filter==='won'&&l.stage!=='Won')return false;
  if(filter==='lost'&&l.stage!=='Lost')return false;
  return [l.name,l.phone,l.email,l.interest,l.source,l.stage,l.notes].join(' ').toLowerCase().includes(term);
 }).sort((a,b)=>(a.followUpAt||'9999').localeCompare(b.followUpAt||'9999'));
 const due=all.filter(l=>['Due today','Overdue'].includes(leadDueStatus(l))).length;
 const pipeline=all.filter(l=>!['Won','Lost'].includes(l.stage)).length;
 return `${sectionHead('Leads & mini CRM','Manual leads, reminders, follow-ups, staff owners and customer conversion','crm-new-lead','Add lead')}<div class="stats-grid">${statCard('Active leads',pipeline,'Open opportunities','◎')}${statCard('Follow-ups due',due,'Today or overdue','◷','amber')}${statCard('Won leads',all.filter(l=>l.stage==='Won').length,'Successful conversions','✓','blue')}${statCard('Total leads',all.length,'Visible in this business','♙')}</div><div class="card"><div class="toolbar"><input class="search-input" id="crm-lead-search" value="${esc(ui.leadSearch||'')}" placeholder="Search name, phone, email, interest"/><select class="field" id="crm-lead-filter" style="max-width:220px">${[['active','Open leads'],['followups','Scheduled follow-ups'],['overdue','Due / overdue'],['all','All leads'],['won','Won'],['lost','Lost']].map(([k,v])=>`<option value="${k}" ${filter===k?'selected':''}>${v}</option>`).join('')}</select><button class="button button-soft" data-action="crm-export">Export CSV</button></div><div class="crm-leads-list">${selected.map(l=>{const owner=store.staff.find(s=>s.id===l.assignedTo);return `<article class="crm-lead-row"><div class="crm-contact"><strong>${esc(l.name)}</strong><small>${esc(l.phone)} ${l.email?' · '+esc(l.email):''}</small><small>${esc(l.interest||'General enquiry')} · ${esc(owner?.name||'Unassigned')}</small></div><div class="crm-stages"><span class="badge ${leadDueStatus(l)==='Overdue'?'void':leadDueStatus(l)==='Due today'?'partial':'paid'}">${esc(l.stage)}</span><small>${esc(leadDueStatus(l))}${l.followUpAt?' · '+esc(humanDate(l.followUpAt)):''}</small></div><button class="button button-small" data-action="crm-view-lead" data-id="${esc(l.id)}">Open ›</button></article>`;}).join('')||rowEmpty('No leads match this filter. Add a new lead to start your CRM.')}</div></div><div class="notice">Local demo only: follow-ups appear in the app, not as push notifications. Real reminders, shared staff access and private customer records require a secure cloud backend.</div>`;
}
function leadModal(id){
 const l=id?getLead(id):null;if(l&&!userCanSeeLead(l))return toast('Lead not accessible.','error');
 const ownerOptions=isAdmin()?staffForBusiness():staffForBusiness().filter(s=>s.id===currentUserId);
 showModal(l?'Edit lead':'New lead',`<form id="crm-lead-form"><input name="id" type="hidden" value="${esc(l?.id||'')}"/><div class="form-grid"><label><span class="input-label">Lead name *</span><input name="name" class="field" required maxlength="120" value="${esc(l?.name||'')}" /></label><label><span class="input-label">Phone *</span><input name="phone" type="tel" inputmode="tel" required class="field" maxlength="20" value="${esc(l?.phone||'')}" placeholder="10-digit mobile number" /></label><label><span class="input-label">Email</span><input name="email" type="email" class="field" value="${esc(l?.email||'')}" /></label><label><span class="input-label">Interested in</span><input name="interest" class="field" value="${esc(l?.interest||'')}" placeholder="e.g. Avocado / Gir cow / Plot 11" /></label><label><span class="input-label">Lead source</span><select name="source" class="field">${['Instagram','Facebook','WhatsApp','Website','Referral','Phone call','Walk-in','Other'].map(s=>`<option ${l?.source===s?'selected':''}>${s}</option>`).join('')}</select></label><label><span class="input-label">Stage</span><select name="stage" class="field">${crmStages.map(s=>`<option ${l?.stage===s?'selected':''}>${s}</option>`).join('')}</select></label><label><span class="input-label">Next follow-up date</span><input type="date" name="followUpAt" class="field" value="${esc((l?.followUpAt||'').slice(0,10))}" /></label><label><span class="input-label">Assigned staff</span><select name="assignedTo" class="field">${ownerOptions.map(s=>`<option value="${esc(s.id)}" ${(l?.assignedTo||currentUserId)===s.id?'selected':''}>${esc(s.name)}</option>`).join('')}</select></label><label class="span-2"><span class="input-label">Lead notes</span><textarea name="notes" class="field" rows="3" maxlength="3000" placeholder="Customer requirements, location, budget, preferred callback time">${esc(l?.notes||'')}</textarea></label></div><div class="form-actions"><button class="button" type="button" data-action="close-modal">Cancel</button><button class="button button-primary" type="submit">Save lead</button></div></form>`);
}
function saveLead(form){
 if(!can('leads'))return;const d=Object.fromEntries(new FormData(form));const phone=digits(d.phone);
 if(!String(d.name||'').trim()||phone.length<10||phone.length>13)return toast('Enter name and a valid mobile number.','error');
 const existing=d.id?getLead(d.id):null;if(existing&&!userCanSeeLead(existing))return toast('Not permitted.','error');
 const assignedTo=isAdmin()&&staffForBusiness().some(s=>s.id===d.assignedTo)?d.assignedTo:(existing?.assignedTo||currentUserId);
 const patch={name:d.name.trim(),phone:d.phone.trim(),email:d.email.trim(),interest:d.interest.trim(),source:d.source,stage:crmStages.includes(d.stage)?d.stage:'New',followUpAt:d.followUpAt||'',assignedTo,notes:d.notes.trim(),updatedAt:new Date().toISOString()};
 const duplicate=(db.leads||[]).find(l=>l.id!==existing?.id&&phoneKey(l.phone)===phoneKey(d.phone));
 if(!existing&&duplicate&&!confirm(`A lead with this phone already exists (${duplicate.name}). Create another record?`))return;
 if(existing){const oldStage=existing.stage,oldFollow=existing.followUpAt;Object.assign(existing,patch);if(oldStage!==patch.stage||oldFollow!==patch.followUpAt)existing.activities.unshift({id:uid('activity'),at:new Date().toISOString(),actorId:currentUserId,text:`Stage: ${oldStage} → ${patch.stage}; next follow-up: ${patch.followUpAt||'none'}`});}
 else{const l={...patch,id:uid('lead'),businessId:store.activeBusinessId,createdAt:new Date().toISOString(),createdBy:currentUserId,customerId:null,activities:[{id:uid('activity'),at:new Date().toISOString(),actorId:currentUserId,text:'Lead created'}]};db.leads.push(l);}
 log(existing?'lead_updated':'lead_created',{phone:phoneKey(d.phone)});persist();closeModal();render();toast('Lead saved.');
}
function leadDetailModal(id){
 const l=getLead(id);if(!userCanSeeLead(l))return toast('Lead not accessible.','error');
 const owner=store.staff.find(s=>s.id===l.assignedTo);
 const entries=(l.activities||[]).slice().reverse().slice(0,15);
 showModal('Lead profile',`<div class="crm-profile"><h3>${esc(l.name)} <span class="badge partial">${esc(l.stage)}</span></h3><div class="crm-inline-links"><a href="tel:${esc(digits(l.phone))}">☎ ${esc(l.phone)}</a>${l.email?`<a href="mailto:${esc(l.email)}">✉ ${esc(l.email)}</a>`:''}</div><p><strong>Interested in:</strong> ${esc(l.interest||'Not specified')}<br/><strong>Source:</strong> ${esc(l.source)}<br/><strong>Assigned to:</strong> ${esc(owner?.name||'Unassigned')}<br/><strong>Follow-up:</strong> ${l.followUpAt?humanDate(l.followUpAt):'Not scheduled'}</p><p>${esc(l.notes||'No notes')}</p>${l.customerId?`<div class="notice">Linked to customer ${esc(getCustomer(l.customerId)?.name||l.customerId)}</div>`:''}</div><div class="crm-activity"><h3>Follow-up history</h3>${entries.map(a=>`<div class="crm-activity-line"><small>${new Date(a.at).toLocaleString('en-IN')}</small><div>${esc(a.text)}</div></div>`).join('')||'<p class="helper-text">No interactions recorded.</p>'}</div><form id="crm-note-form"><input name="leadId" type="hidden" value="${esc(l.id)}"/><label><span class="input-label">Add call / WhatsApp / meeting note</span><textarea name="note" required rows="2" maxlength="1500" class="field" placeholder="Spoke to customer; interested in 20 saplings…"></textarea></label><label><span class="input-label">Update next follow-up</span><input type="date" name="followUpAt" class="field" value="${esc(l.followUpAt||'')}"/></label><div class="form-actions"><button type="button" class="button" data-action="crm-edit-lead" data-id="${esc(l.id)}">Edit lead</button>${isAdmin()&&!l.customerId?`<button type="button" class="button button-soft" data-action="crm-convert-customer" data-id="${esc(l.id)}">Convert to customer</button>`:''}<button class="button button-primary" type="submit">Save follow-up</button></div></form>`);
}
function crmSaveNote(form){const d=Object.fromEntries(new FormData(form));const l=getLead(d.leadId);if(!userCanSeeLead(l))return;const note=String(d.note||'').trim();if(!note)return toast('Enter follow-up note.','error');l.activities=l.activities||[];l.activities.unshift({id:uid('activity'),at:new Date().toISOString(),actorId:currentUserId,text:note});l.followUpAt=d.followUpAt||'';l.updatedAt=new Date().toISOString();log('lead_followup',{id:l.id});persist();leadDetailModal(l.id);toast('Follow-up saved.');}
function convertLeadToCustomer(id){
 const l=getLead(id);if(!isAdmin()||!l)return;
 if(l.customerId)return toast('Already linked to customer.','error');
 const match=db.customers.find(c=>c.id!=='walk-in'&&phoneKey(c.phone)===phoneKey(l.phone));
 let customer=match;
 if(match&&!confirm(`An existing customer has this phone: ${match.name}. Link this lead to the existing customer?`))return;
 if(!match){customer={id:uid('customer'),name:l.name,phone:l.phone,email:l.email||'',address:'',shippingAddress:'',shippingCity:'',shippingState:'',shippingPincode:'',gstin:'',openingBalance:0,creditLimit:0,notes:`Lead source: ${l.source}. ${l.notes||''}`,customerInvoiceCounter:0,customerCode:nextCustomerCode()};db.customers.push(customer);}
 l.customerId=customer.id;l.stage='Won';l.updatedAt=new Date().toISOString();l.activities.unshift({id:uid('activity'),at:l.updatedAt,actorId:currentUserId,text:`Converted to customer ${customer.customerCode}`});log('lead_converted',{leadId:l.id,customerId:customer.id});persist();closeModal();render();toast('Lead linked to customer: '+customer.name);
}
function nextCustomerCode(){let code;const used=new Set(db.customers.map(c=>c.customerCode));do{code=`${db.business.invoicePrefix||'DB'}-C${String(++db.customerCounter).padStart(5,'0')}`;}while(used.has(code));return code;}
function customerHistory(id){
 const c=db.customers.find(c=>c.id===id);if(!c)return;const inv=db.invoices.filter(i=>i.customerId===id&&(isAdmin()||i.createdBy===currentUserId||i.salespersonId===currentUserId)).slice().reverse();const quotes=db.estimates.filter(q=>q.customerId===id&&(isAdmin()||q.createdBy===currentUserId));
 showModal('Customer & invoices',`<div class="crm-profile"><h3>${esc(c.name)}</h3><p><strong>Customer ID:</strong> ${esc(clientCode(c))}<br/><strong>Phone:</strong> ${esc(c.phone||'—')}<br/><strong>Email:</strong> ${esc(c.email||'—')}<br/><strong>Shipping:</strong> ${esc(c.shippingAddress||c.address||'—')}</p><div class="crm-inline-links"><a href="tel:${esc(digits(c.phone))}">☎ Call</a>${c.email?`<a href="mailto:${esc(c.email)}">✉ Email</a>`:''}</div></div><h3 style="margin:16px 0 9px">Invoices (${inv.length})</h3>${inv.map(i=>`<button class="crm-history-row" data-action="crm-open-invoice" data-id="${esc(i.id)}"><strong>${esc(i.number)}</strong><span>${esc(i.customerInvoiceRef||'')} · ${humanDate(i.date)} · ${money(i.total)}</span></button>`).join('')||'<p class="helper-text">No invoices visible for this staff member.</p>'}<p class="helper-text">${quotes.length} quotations linked to this customer.</p>`);
}
function saveShippingDraftFields(){const d=ui.draft;if(!d)return;const c=getCustomer(d.customerId)||{};for(const k of ['shippingName','shippingPhone','shippingAddress','shippingCity','shippingState','shippingPincode']){if(!d[k])d[k]=(k==='shippingName'?c.shippingName||c.name:k==='shippingPhone'?c.shippingPhone||c.phone:c[k])||'';}}
function shippingFieldsBlock(){const d=ui.draft,c=getCustomer(d?.customerId)||{};return `<div class="card"><div class="card-header"><h3>Shipping & e-commerce label</h3><div class="card-sub">Delivery details are stored with this invoice; no changes to customer master</div></div><div class="form-grid"><label><span class="input-label">Recipient name</span><input class="field" data-draft="shippingName" value="${esc(shippingValue(d,'shippingName',c)||c.name||'')}"/></label><label><span class="input-label">Recipient phone</span><input class="field" data-draft="shippingPhone" type="tel" value="${esc(shippingValue(d,'shippingPhone',c)||c.phone||'')}"/></label><label class="span-2"><span class="input-label">Shipping address</span><textarea class="field" data-draft="shippingAddress" rows="2" placeholder="House / building, road, landmark">${esc(shippingValue(d,'shippingAddress',c)||c.address||'')}</textarea></label><label><span class="input-label">City</span><input class="field" data-draft="shippingCity" value="${esc(shippingValue(d,'shippingCity',c))}"/></label><label><span class="input-label">State</span><input class="field" data-draft="shippingState" value="${esc(shippingValue(d,'shippingState',c))}"/></label><label><span class="input-label">PIN code</span><input class="field" data-draft="shippingPincode" inputmode="numeric" maxlength="6" value="${esc(shippingValue(d,'shippingPincode',c))}"/></label></div><p class="helper-text">After saving the invoice, use Print 4×6 shipping label or A4 (4-up). Tracking and courier API integration can be added later.</p></div>`;}
function shippingLabelBody(invoice){
 const sh=invoice.shippingSnapshot||invoice.customerSnapshot||{};const b=invoice.businessSnapshot||db.business;
 return `<div class="shipping-label"><div class="ship-header"><div><strong>SHIP TO</strong><h2>${esc(sh.name||invoice.customerName||'')}</h2></div><span class="ship-brand">DARVI<br/>GROUP</span></div><div class="ship-address">${esc(sh.address||'Shipping address not provided').replace(/\n/g,'<br/>')}${sh.city?'<br/>'+esc(sh.city):''}${sh.state?', '+esc(sh.state):''}${sh.pincode?' – '+esc(sh.pincode):''}</div><div class="ship-phone">☎ ${esc(sh.phone||'No recipient phone')}</div><div class="ship-order"><div><small>ORDER / INVOICE</small><strong>${esc(invoice.number)}</strong></div><div><small>CUSTOMER REF</small><strong>${esc(invoice.customerInvoiceRef||invoice.customerId)}</strong></div></div><div class="ship-footer"><div><small>FROM</small><strong>${esc(b.name)}</strong><span>${esc(b.address||'Hubballi, Karnataka')}</span></div><div><small>PAYMENT</small><strong>${round(invoice.total-invoice.paidAmount)>0?'AMOUNT DUE':'PAID'}</strong></div></div><div class="ship-bottom">E-commerce shipping label · Attach courier barcode after fulfilment</div></div>`;
}
function printShippingLabel(id,format='shipping4x6'){
 const inv=db.invoices.find(i=>i.id===id);if(!inv||(!isAdmin()&&inv.createdBy!==currentUserId&&inv.salespersonId!==currentUserId))return;
 const sh=inv.shippingSnapshot||inv.customerSnapshot||{};
 if(!sh.address&&!confirm('No shipping address is saved. Print with an empty address?'))return;
 document.body.dataset.printFormat=format;
 qs('#print-sheet').innerHTML=format==='shippingA4'?`<div class="shipping-a4-grid">${[0,1,2,3].map(()=>shippingLabelBody(inv)).join('')}</div>`:shippingLabelBody(inv);
 window.print();
}
function exportLeadsCSV(){if(!can('leads'))return;const hdr=['Lead ID','Business','Name','Phone','Email','Interest','Source','Stage','Assigned staff','Next follow-up','Created'];const rows=visibleLeads().map(l=>[l.id,db.business.name,l.name,l.phone,l.email,l.interest,l.source,l.stage,store.staff.find(s=>s.id===l.assignedTo)?.name||'',l.followUpAt,l.createdAt]);const csv=[hdr,...rows].map(row=>row.map(c=>'"'+String(c??'').replace(/"/g,'""')+'"').join(',')).join('\r\n');downloadFile('\ufeff'+csv,`darvi-leads-${store.activeBusinessId}-${localDate()}.csv`,'text/csv;charset=utf-8');}
