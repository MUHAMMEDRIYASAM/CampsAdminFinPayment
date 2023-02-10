OB.AP = {};
OB.AP.Reversal = {};

OB.AP.Reversal.resetFields = function (item, view, form, grid) {
 var revType = form.getItem('revType').getValue();
 var type = null;
 switch(revType) {
  case revType="PR":
	type = 'Payment'
	break;
  case revType="GR":
	type = 'Goods Receipt'
	break;
  case revType="GST":
	type = 'GST Postings'
	break;
  case revType="INV":
	type = 'Invoice'
	break;
  default:
	type='Reversal Type'
};
 
  form.getItem('fin_payment_id').setValue(null);
  form.getItem('c_invoice_id').setValue(null);
  form.getItem('m_inout_id').setValue(null);
  form.getItem('gst_postings_id').setValue(null);
  view.messageBar.setMessage(isc.OBMessageBar.TYPE_INFO, 'Please fill the following fields:', type);

};