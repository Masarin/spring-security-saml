/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sample.web;

import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml.MetadataResolver;
import org.springframework.security.saml.SamlTransformer;
import org.springframework.security.saml.config.ExternalProviderConfiguration;
import org.springframework.security.saml.saml2.authentication.AuthenticationRequest;
import org.springframework.security.saml.saml2.authentication.NameIdPrincipal;
import org.springframework.security.saml.saml2.authentication.Response;
import org.springframework.security.saml.saml2.metadata.Endpoint;
import org.springframework.security.saml.saml2.metadata.IdentityProviderMetadata;
import org.springframework.security.saml.saml2.metadata.ServiceProviderMetadata;
import org.springframework.security.saml.spi.Defaults;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import sample.config.AppConfig;

@Controller
public class ServiceProviderController implements InitializingBean {

    private AppConfig configuration;
    private SamlTransformer transformer;
    private Defaults defaults;
    private MetadataResolver resolver;

    @Autowired
    public void setTransformer(SamlTransformer transformer) {
        this.transformer = transformer;
    }

    @Autowired
    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    @Autowired
    public void setMetadataResolver(MetadataResolver resolver) {
        this.resolver = resolver;
    }

    @Autowired
    public void setAppConfig(AppConfig config) {
        this.configuration = config;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
    }

    @RequestMapping(value = {"/", "/index", "logged-in"})
    public String home() {
        return "logged-in";
    }

    @RequestMapping("/saml/sp/select")
    public String selectProvider(HttpServletRequest request, Model model) {
        List<ModelProvider> providers = new LinkedList<>();
            configuration.getServiceProvider().getProviders().stream().forEach(
                p -> {
                    try {
                        ModelProvider mp = new ModelProvider().setLinkText(p.getLinktext()).setRedirect(getDiscoveryRedirect(request, p));
                        providers.add(mp);
                    } catch (Exception x) {
                        x.printStackTrace();
                    }
                }
            );
        model.addAttribute("idps", providers);
        return "select-provider";
    }

    @GetMapping(value = "/saml/sp/metadata", produces = MediaType.TEXT_XML_VALUE)
    public @ResponseBody() String metadata(HttpServletRequest request) {
        ServiceProviderMetadata metadata = getServiceProviderMetadata(request);
        return transformer.toXml(metadata);
    }

    @RequestMapping("/saml/sp/discovery")
    public View discovery(HttpServletRequest request,
                          @RequestParam(name = "idp", required = true) String entityId) {
        //create authnrequest
        IdentityProviderMetadata m = resolver.resolveIdentityProvider(entityId);
        ServiceProviderMetadata local = getServiceProviderMetadata(request);
        AuthenticationRequest authenticationRequest = getDefaults().authenticationRequest(local, m);
        String url = getAuthnRequestRedirect(request, m, authenticationRequest);
        return new RedirectView(url);
    }

    @RequestMapping("/saml/sp/SSO")
    public View sso(HttpServletRequest request,
                    @RequestParam(name = "SAMLResponse", required = true) String response) {
        //receive assertion
        String xml = transformer.samlDecode(response, true);
        //extract basic data
        Response r = (Response) transformer.resolve(xml, null);
        IdentityProviderMetadata identityProviderMetadata = resolver.resolveIdentityProvider(r);
        //validate signature
        r = (Response) transformer.resolve(xml, identityProviderMetadata.getIdentityProvider().getKeys());
        NameIdPrincipal principal = (NameIdPrincipal) r.getAssertions().get(0).getSubject().getPrincipal();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(principal.getValue(), null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(token);
        RedirectView view = new RedirectView("/");
        view.setContextRelative(true);
        return view;
    }

    protected String getAuthnRequestRedirect(HttpServletRequest request,
                                             IdentityProviderMetadata m,
                                             AuthenticationRequest authenticationRequest) {
        String xml = transformer.toXml(authenticationRequest);
        String deflated = transformer.samlEncode(xml, true);
        Endpoint endpoint = m.getIdentityProvider().getSingleSignOnService().get(0);
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(endpoint.getLocation());
        url.queryParam("SAMLRequest", URLEncoder.encode(deflated));
        return url.build(true).toUriString();
    }

    protected String getDiscoveryRedirect(HttpServletRequest request, ExternalProviderConfiguration p) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(getBasePath(request));
        builder.pathSegment("saml/sp/discovery");
        IdentityProviderMetadata metadata = resolver.resolveIdentityProvider(p);
        builder.queryParam("idp", metadata.getEntityId());
        return builder.build().toUriString();
    }

    protected ServiceProviderMetadata getServiceProviderMetadata(HttpServletRequest request) {
        return getMetadataResolver().getLocalServiceProvider(getBasePath(request));
    }

    protected String getBasePath(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public MetadataResolver getMetadataResolver() {
        return resolver;
    }
}
