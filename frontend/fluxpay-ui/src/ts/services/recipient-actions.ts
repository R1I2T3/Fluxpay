/** Plan 5 consumes the documented DELETE contract without changing shared API anchors. */
export async function deleteRecipient(id:string):Promise<void>{
  const response=await fetch(((window as any).FLUXPAY_API_URL||'')+'/api/recipients/'+encodeURIComponent(id),{
    method:'DELETE',headers:{Accept:'application/json',Authorization:'Bearer '+(sessionStorage.getItem('fluxpay.token')||'')}
  });
  if(response.status===401){sessionStorage.removeItem('fluxpay.token');window.dispatchEvent(new Event('fluxpay:expired'));}
  if(!response.ok){
    const body=await response.json().catch(()=>({}));
    if(response.status===405)throw new Error('Recipient deletion is not available on this backend yet. Your recipient has not been removed.');
    throw new Error(body.message||'Recipient could not be deleted. Please refresh and try again.');
  }
}
