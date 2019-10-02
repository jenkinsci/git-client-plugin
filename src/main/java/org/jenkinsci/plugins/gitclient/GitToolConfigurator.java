package org.jenkinsci.plugins.gitclient;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.plugins.git.GitTool;
import hudson.tools.ToolProperty;
import io.jenkins.plugins.casc.Attribute;
import io.jenkins.plugins.casc.BaseConfigurator;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.Configurator;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import io.jenkins.plugins.casc.model.Sequence;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Extension(optional = true)
public class GitToolConfigurator extends BaseConfigurator<GitTool> {

    @NonNull
    @Override
    public String getName() {
        return "git";
    }

    @Override
    public String getDisplayName() {
        return "Git";
    }

    @Override
    public Class getTarget() {
        return GitTool.class;
    }

    @Override
    public boolean canConfigure(Class clazz) {
        return clazz == GitTool.class;
    }

    @NonNull
    @Override
    public Class getImplementedAPI() {
        return getTarget();
    }

    @NonNull
    @Override
    public List<Configurator<GitTool>> getConfigurators(ConfigurationContext context) {
        return Collections.singletonList(this);
    }

    @NonNull
    @Override
    public List<Attribute<GitTool, ?>> getAttributes() {
        Attribute<GitTool, String> name = new Attribute<>("name", String.class);
        Attribute<GitTool, String> home = new Attribute<>("home", String.class);
        Attribute<GitTool, ToolProperty> p = new Attribute<>("properties", ToolProperty.class);
        p.multiple(true);
        return Arrays.asList(name, home, p);
    }

    @Override
    protected GitTool instance(Mapping mapping, ConfigurationContext context) throws ConfiguratorException {
        final CNode mproperties = mapping.remove("properties");
        final String name = mapping.getScalarValue("name");
        if (JGitTool.MAGIC_EXENAME.equals(name)) {
            mapping.remove("home"); //Ignored but could be added, so removing to not fail handleUnknown
            return new JGitTool(instantiateProperties(mproperties, context));
        } else if (JGitApacheTool.MAGIC_EXENAME.equals(name)) {
            mapping.remove("home");  //Ignored but could be added, so removing to not fail handleUnknown
            return new JGitApacheTool(instantiateProperties(mproperties, context));
        } else {
            if (mapping.get("home") == null) {
                throw new ConfiguratorException(this, "Home required for cli git configuration.");
            }
            String home = mapping.getScalarValue("home");
            return new GitTool(name, home, instantiateProperties(mproperties, context));
        }
    }

    @Nonnull
    private List<ToolProperty<?>> instantiateProperties(@CheckForNull CNode props, @Nonnull ConfigurationContext context) throws ConfiguratorException {
        final Configurator<ToolProperty> configurator = context.lookupOrFail(ToolProperty.class);
        List<ToolProperty<?>> toolProperties = new ArrayList<>();
        if (props == null) {
            return toolProperties;
        }
        if (props instanceof Sequence) {
            Sequence s = (Sequence) props;
            for (CNode cNode : s) {
                toolProperties.add(configurator.configure(cNode, context));
            }
        } else {
            toolProperties.add(configurator.configure(props, context));
        }
        return toolProperties;
    }

    @Override
    public CNode describe(GitTool instance, ConfigurationContext context) throws Exception {
        Mapping mapping = new Mapping();
        if (instance instanceof JGitTool) {
            mapping.put("name", JGitTool.MAGIC_EXENAME);
        } else if (instance instanceof JGitApacheTool) {
            mapping.put("name", JGitApacheTool.MAGIC_EXENAME);
        } else {
            mapping.put("name", instance.getName());
            mapping.put("home", instance.getHome());
        }
        if (instance.getProperties() != null && !instance.getProperties().isEmpty()) {
            final Configurator<ToolProperty> configurator = context.lookupOrFail(ToolProperty.class);
            Sequence s = new Sequence(instance.getProperties().size());
            for (ToolProperty<?> property : instance.getProperties()) {
                s.add(configurator.describe(property, context));
            }
            mapping.put("properties", s);
        }
        return mapping;
    }
}
